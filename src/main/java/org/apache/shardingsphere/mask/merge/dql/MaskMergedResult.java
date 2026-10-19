/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.shardingsphere.mask.merge.dql;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import org.apache.shardingsphere.infra.binder.context.segment.select.projection.Projection;
import org.apache.shardingsphere.infra.binder.context.segment.select.projection.impl.AggregationProjection;
import org.apache.shardingsphere.infra.binder.context.segment.select.projection.impl.ExpressionProjection;
import org.apache.shardingsphere.infra.binder.context.segment.select.projection.impl.SubqueryProjection;
import org.apache.shardingsphere.infra.binder.context.statement.type.dml.SelectStatementContext;
import org.apache.shardingsphere.infra.merge.result.MergedResult;
import org.apache.shardingsphere.infra.merge.result.impl.decorator.DecoratorMergedResult;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.mask.rule.MaskRule;
import org.apache.shardingsphere.mask.spi.MaskAlgorithm;
import org.apache.shardingsphere.sql.parser.statement.core.extractor.ExpressionExtractor;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.bound.ColumnSegmentBoundInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hardened mask result decorator.
 *
 * <p>Apache ShardingSphere 5.5.3 returns an unbound projection unchanged. That
 * is correct for constants, but it also allows an expression such as
 * {@code CONCAT('', id_card)} to bypass a configured mask. This replacement
 * keeps normal column masking intact and rejects an unbound projection whenever
 * its expression lineage contains a configured sensitive source column.</p>
 */
public final class MaskMergedResult extends DecoratorMergedResult {

    private static final Logger SECURITY_LOGGER = LoggerFactory.getLogger("gateway.mask.security");

    private static final String UNSAFE_PROJECTION_MESSAGE =
            "Sensitive projection cannot be returned because its output lineage is not directly maskable.";

    private final ShardingSphereDatabase database;

    private final ShardingSphereMetaData metaData;

    private final SelectStatementContext selectStatementContext;
    private final com.example.doris.gateway.security.UserOutputPlan userPlan;
    private final java.util.Map<Integer, Optional<MaskAlgorithm>> columnAlgorithms = new java.util.HashMap<>();

    public MaskMergedResult(final ShardingSphereDatabase database, final ShardingSphereMetaData metaData,
                            final SelectStatementContext selectStatementContext, final MergedResult mergedResult) {
        super(mergedResult);
        this.database = database;
        this.metaData = metaData;
        this.selectStatementContext = selectStatementContext;
        this.userPlan = com.example.doris.gateway.security.UserOutputPlan.capture(database.getName());
        // Validate all result projections before any row is emitted.
        for (int i = 1; i <= selectStatementContext.getProjectionsContext().getExpandProjections().size(); i++) {
            Optional<ColumnSegmentBoundInfo> bound = selectStatementContext.findColumnBoundInfo(i);
            if (bound.isPresent()) columnAlgorithms.put(i, findMaskAlgorithm(bound.get()));
            else {
                try { rejectUnsafeProjection(i); } catch (SQLException ex) { throw new IllegalStateException(ex.getMessage(), ex); }
                columnAlgorithms.put(i, Optional.empty());
            }
        }
        emitResultLineage();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Object getValue(final int columnIndex, final Class<?> type) throws SQLException {
        Optional<MaskAlgorithm> maskAlgorithm = columnAlgorithms.get(columnIndex);
        if (maskAlgorithm == null) throw new SQLException("结果列没有经过保护决策校验，拒绝返回。", "42000");
        if (!maskAlgorithm.isPresent()) {
            return getMergedResult().getValue(columnIndex, type);
        }
        Object originalValue = getMergedResult().getValue(columnIndex, Object.class);
        return null == originalValue ? null : maskAlgorithm.get().mask(originalValue);
    }
    public boolean isExportProtected(int columnIndex){Optional<MaskAlgorithm> algorithm=columnAlgorithms.get(columnIndex);return algorithm!=null&&algorithm.isPresent();}
    public Object exportOriginalValue(int columnIndex)throws SQLException {
        if(!isExportProtected(columnIndex))throw new SQLException("禁止提取非保护列的原值。");
        return getMergedResult().getValue(columnIndex,Object.class);
    }
    public List<java.util.Map<String,String>> exportSources(){
        List<java.util.Map<String,String>> result=new ArrayList<>();
        for(Integer index:columnAlgorithms.keySet())if(isExportProtected(index)){
            ColumnSegmentBoundInfo bound=selectStatementContext.findColumnBoundInfo(index).orElseThrow(()->new IllegalStateException("缺少加密导出来源。"));
            java.util.Map<String,String> source=new java.util.LinkedHashMap<>();
            source.put("database",bound.getOriginalDatabase().getValue());source.put("table",bound.getOriginalTable().getValue());source.put("column",bound.getOriginalColumn().getValue());result.add(source);
        }
        return result;
    }

    private void rejectUnsafeProjection(final int columnIndex) throws SQLException {
        List<Projection> projections = selectStatementContext.getProjectionsContext().getExpandProjections();
        if (columnIndex < 1 || columnIndex > projections.size()) {
            return;
        }
        for (ColumnSegment each : extractProjectionColumns(projections.get(columnIndex - 1))) {
            ColumnSegmentBoundInfo boundInfo = each.getColumnBoundInfo();
            if (null == boundInfo && hasMaskRule()) {
                SECURITY_LOGGER.warn("mask_security_decision=DENY reason=UNRESOLVED_PROJECTION_LINEAGE database={} result_column_index={}",
                        database.getName(), columnIndex);
                throw new SQLException(UNSAFE_PROJECTION_MESSAGE, "42000", 30001);
            }
            if (null != boundInfo && findMaskAlgorithm(boundInfo).isPresent()) {
                SECURITY_LOGGER.warn("mask_security_decision=DENY reason=SENSITIVE_DERIVED_PROJECTION database={} table={} column={} result_column_index={}",
                        boundInfo.getOriginalDatabase().getValue(), boundInfo.getOriginalTable().getValue(),
                        boundInfo.getOriginalColumn().getValue(), columnIndex);
                throw new SQLException(UNSAFE_PROJECTION_MESSAGE, "42000", 30001);
            }
        }
    }

    private boolean hasMaskRule() {
        return userPlan != null || database.getRuleMetaData().findSingleRule(MaskRule.class).isPresent();
    }

    private Collection<ColumnSegment> extractProjectionColumns(final Projection projection) {
        if (projection instanceof ExpressionProjection) {
            ExpressionProjection expressionProjection = (ExpressionProjection) projection;
            if (null == expressionProjection.getExpressionSegment().getExpr()) {
                return Collections.emptyList();
            }
            return ExpressionExtractor.extractColumns(expressionProjection.getExpressionSegment().getExpr(), true);
        }
        if (projection instanceof AggregationProjection) {
            return ExpressionExtractor.extractColumns(((AggregationProjection) projection).getAggregationSegment(), true);
        }
        if (projection instanceof SubqueryProjection) {
            return extractProjectionColumns(((SubqueryProjection) projection).getProjection());
        }
        return Collections.emptyList();
    }

    private void emitResultLineage() {
        List<Projection> projections = selectStatementContext.getProjectionsContext().getExpandProjections();
        List<LineageEventEmitter.Edge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < projections.size(); i++) {
            Projection projection = projections.get(i);
            String targetColumn = projection.getColumnLabel();
            Optional<ColumnSegmentBoundInfo> direct = selectStatementContext.findColumnBoundInfo(i + 1);
            if (direct.isPresent()) {
                addEdge(edges, seen, direct.get(), targetColumn, "DIRECT");
                continue;
            }
            for (ColumnSegment each : extractProjectionColumns(projection)) {
                if (each.getColumnBoundInfo() != null) {
                    addEdge(edges, seen, each.getColumnBoundInfo(), targetColumn, "EXPRESSION");
                }
            }
        }
        LineageEventEmitter.emitQuery(database.getName(), edges);
    }

    private void addEdge(final List<LineageEventEmitter.Edge> edges, final Set<String> seen,
                         final ColumnSegmentBoundInfo source, final String targetColumn, final String transformType) {
        String sourceDatabase = source.getOriginalDatabase().getValue();
        String sourceTable = source.getOriginalTable().getValue();
        String sourceColumn = source.getOriginalColumn().getValue();
        String key = sourceDatabase + '\u0000' + sourceTable + '\u0000' + sourceColumn + '\u0000' + targetColumn;
        if (seen.add(key)) {
            edges.add(new LineageEventEmitter.Edge(sourceDatabase, sourceTable, sourceColumn,
                    database.getName(), "__RESULT__", targetColumn, transformType));
        }
    }

    private Optional<MaskAlgorithm> findMaskAlgorithm(final ColumnSegmentBoundInfo boundInfo) {
        String originalDatabaseName = boundInfo.getOriginalDatabase().getValue();
        boolean protectionRequired = false;
        if (userPlan != null) {
            com.example.doris.gateway.security.UserOutputPlan.Decision decision = userPlan.decision(originalDatabaseName,
                    boundInfo.getOriginalTable().getValue(), boundInfo.getOriginalColumn().getValue());
            if ("DENY".equals(decision.action)) throw new IllegalStateException("当前用户不允许返回该敏感字段。");
            if ("CLEAR".equals(decision.action)) return Optional.empty();
            if ("MASK".equals(decision.action)) return Optional.of(decision.algorithm);
            protectionRequired = decision.protectionRequired;
        }
        ShardingSphereDatabase targetDatabase = metaData.containsDatabase(originalDatabaseName)
                ? metaData.getDatabase(originalDatabaseName)
                : database;
        Optional<MaskRule> rule = targetDatabase.getRuleMetaData().findSingleRule(MaskRule.class);
        String originalTableName = boundInfo.getOriginalTable().getValue();
        String originalColumnName = boundInfo.getOriginalColumn().getValue();
        Optional<MaskAlgorithm> algorithm = rule.isPresent()
                ? rule.get().findMaskTable(originalTableName).flatMap(optional -> optional.findAlgorithm(originalColumnName)) : Optional.empty();
        return com.example.doris.gateway.security.UserOutputPlan.requirePublishedProtection(algorithm, protectionRequired);
    }
}
