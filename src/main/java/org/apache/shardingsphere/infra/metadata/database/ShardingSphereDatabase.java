/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.shardingsphere.infra.metadata.database;

import org.apache.shardingsphere.database.connector.core.type.DatabaseType;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.infra.config.rule.decorator.RuleConfigurationDecorator;
import org.apache.shardingsphere.infra.exception.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.exception.kernel.metadata.resource.storageunit.MissingRequiredStorageUnitsException;
import org.apache.shardingsphere.infra.metadata.database.resource.ResourceMetaData;
import org.apache.shardingsphere.infra.metadata.database.rule.RuleMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.identifier.ShardingSphereIdentifier;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.attribute.datanode.MutableDataNodeRuleAttribute;
import org.apache.shardingsphere.infra.rule.attribute.datasource.DataSourceMapperRuleAttribute;
import org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ShardingSphere database with a compatibility guard for case-only duplicate
 * schema metadata produced by MySQL-protocol DDL refreshes.
 */
public final class ShardingSphereDatabase {

    private final String name;

    private final DatabaseType protocolType;

    private final ResourceMetaData resourceMetaData;

    private final RuleMetaData ruleMetaData;

    private final Map<ShardingSphereIdentifier, ShardingSphereSchema> schemas;

    public ShardingSphereDatabase(final String name, final DatabaseType protocolType, final ResourceMetaData resourceMetaData,
                                  final RuleMetaData ruleMetaData, final Collection<ShardingSphereSchema> schemas) {
        this.name = name;
        this.protocolType = protocolType;
        this.resourceMetaData = resourceMetaData;
        this.ruleMetaData = ruleMetaData;
        this.schemas = new ConcurrentHashMap<>();
        for (ShardingSphereSchema each : schemas) {
            mergeSchema(each);
        }
    }

    private void mergeSchema(final ShardingSphereSchema candidate) {
        ShardingSphereIdentifier identifier = new ShardingSphereIdentifier(candidate.getName());
        ShardingSphereSchema existing = schemas.get(identifier);
        if (null == existing) {
            schemas.put(identifier, candidate);
            return;
        }
        ShardingSphereSchema preferred = candidate.getName().equals(name) && !existing.getName().equals(name) ? candidate : existing;
        ShardingSphereSchema additional = preferred == existing ? candidate : existing;
        additional.getAllTables().stream().filter(each -> !preferred.containsTable(each.getName())).forEach(preferred::putTable);
        additional.getAllViews().stream().filter(each -> !preferred.containsView(each.getName())).forEach(preferred::putView);
        schemas.put(identifier, preferred);
    }

    public Collection<ShardingSphereSchema> getAllSchemas() {
        return schemas.values();
    }

    public boolean containsSchema(final String schemaName) {
        return schemas.containsKey(new ShardingSphereIdentifier(schemaName));
    }

    public ShardingSphereSchema getSchema(final String schemaName) {
        return schemas.get(new ShardingSphereIdentifier(schemaName));
    }

    public void addSchema(final ShardingSphereSchema schema) {
        schemas.put(new ShardingSphereIdentifier(schema.getName()), schema);
    }

    public void dropSchema(final String schemaName) {
        schemas.remove(new ShardingSphereIdentifier(schemaName));
    }

    public boolean isComplete() {
        return !ruleMetaData.getRules().isEmpty() && !resourceMetaData.getStorageUnits().isEmpty();
    }

    public boolean containsDataSource() {
        return !resourceMetaData.getStorageUnits().isEmpty();
    }

    public synchronized void reloadRules() {
        Collection<ShardingSphereRule> toBeReloadedRules = ruleMetaData.getRules().stream()
                .filter(each -> each.getAttributes().findAttribute(MutableDataNodeRuleAttribute.class).isPresent()).collect(Collectors.toList());
        RuleConfiguration ruleConfig = toBeReloadedRules.stream().map(ShardingSphereRule::getConfiguration).findFirst().orElse(null);
        Collection<ShardingSphereRule> rules = new LinkedList<>(ruleMetaData.getRules());
        toBeReloadedRules.stream().findFirst().ifPresent(optional -> {
            rules.removeAll(toBeReloadedRules);
            Map<String, DataSource> dataSources = resourceMetaData.getStorageUnits().entrySet().stream()
                    .collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().getDataSource(), (oldValue, currentValue) -> oldValue, LinkedHashMap::new));
            rules.add(optional.getAttributes().getAttribute(MutableDataNodeRuleAttribute.class).reloadRule(ruleConfig, name, dataSources, rules));
        });
        ruleMetaData.getRules().clear();
        ruleMetaData.getRules().addAll(rules);
    }

    public void checkStorageUnitsExisted(final Collection<String> storageUnitNames) {
        Collection<String> notExistedDataSources = resourceMetaData.getNotExistedDataSources(storageUnitNames);
        Collection<String> logicDataSources = ruleMetaData.getAttributes(DataSourceMapperRuleAttribute.class).stream()
                .flatMap(each -> each.getDataSourceMapper().keySet().stream()).collect(Collectors.toSet());
        notExistedDataSources.removeIf(logicDataSources::contains);
        ShardingSpherePreconditions.checkMustEmpty(notExistedDataSources, () -> new MissingRequiredStorageUnitsException(name, notExistedDataSources));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuleConfiguration decorateRuleConfiguration(final RuleConfiguration ruleConfig) {
        Optional<RuleConfigurationDecorator> decorator = TypedSPILoader.findService(RuleConfigurationDecorator.class, ruleConfig.getClass());
        if (!decorator.isPresent()) {
            return ruleConfig;
        }
        Map<String, DataSource> dataSources = resourceMetaData.getStorageUnits().entrySet().stream()
                .collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().getDataSource(), (oldValue, currentValue) -> oldValue, LinkedHashMap::new));
        return decorator.get().decorate(name, dataSources, ruleMetaData.getRules(), ruleConfig);
    }

    public String getName() {
        return name;
    }

    public DatabaseType getProtocolType() {
        return protocolType;
    }

    public ResourceMetaData getResourceMetaData() {
        return resourceMetaData;
    }

    public RuleMetaData getRuleMetaData() {
        return ruleMetaData;
    }
}
