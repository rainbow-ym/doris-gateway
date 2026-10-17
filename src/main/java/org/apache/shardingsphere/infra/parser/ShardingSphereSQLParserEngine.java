/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.shardingsphere.infra.parser;

import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.apache.shardingsphere.database.connector.core.type.DatabaseType;
import org.apache.shardingsphere.database.exception.core.exception.syntax.sql.DialectSQLParsingException;
import org.apache.shardingsphere.distsql.parser.engine.api.DistSQLStatementParserEngine;
import org.apache.shardingsphere.infra.parser.sql.SQLStatementParserEngine;
import org.apache.shardingsphere.infra.parser.sql.SQLStatementParserEngineFactory;
import org.apache.shardingsphere.sql.parser.engine.api.CacheOption;
import org.apache.shardingsphere.sql.parser.engine.exception.SQLParsingException;
import org.apache.shardingsphere.sql.parser.statement.core.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.statement.core.util.SQLUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShardingSphere parser engine with a narrow Doris MATERIALIZED VIEW bridge.
 *
 * <p>Doris speaks the MySQL wire protocol, but MATERIALIZED VIEW is not in the
 * MySQL grammar. Replacing Doris-only words with equal-length whitespace keeps
 * every parser segment offset aligned with the original SQL, so the existing
 * routing and rewrite pipeline can safely pass the original statement through.</p>
 */
public final class ShardingSphereSQLParserEngine implements SQLParserEngine {

    private static final Pattern CREATE_MATERIALIZED_VIEW = Pattern.compile(
            "(?is)^(\\s*CREATE\\s+)MATERIALIZED(\\s+VIEW\\s+(?:`[^`]+`|[\\p{L}\\p{N}_$]+)(?:\\.(?:`[^`]+`|[\\p{L}\\p{N}_$]+))?)(.*?)(\\bAS\\s+SELECT\\b.*)$");
    private static final Pattern DROP_MATERIALIZED_VIEW = Pattern.compile("(?is)^(\\s*DROP\\s+)MATERIALIZED(\\s+VIEW\\b.*)$");

    private final SQLStatementParserEngine sqlStatementParserEngine;
    private final DistSQLStatementParserEngine distSQLStatementParserEngine;

    public ShardingSphereSQLParserEngine(final DatabaseType databaseType, final CacheOption sqlStatementCacheOption, final CacheOption parseTreeCacheOption) {
        sqlStatementParserEngine = SQLStatementParserEngineFactory.getSQLStatementParserEngine(databaseType, sqlStatementCacheOption, parseTreeCacheOption);
        distSQLStatementParserEngine = new DistSQLStatementParserEngine();
    }

    @Override
    public SQLStatement parse(final String sql, final boolean useCache) {
        if (com.example.doris.gateway.security.UserBackendConnections.enabled()
                && com.example.doris.gateway.account.AccountCommand.candidate(sql)) {
            com.example.doris.gateway.account.AccountCommand.parse(sql);
            return new com.example.doris.gateway.account.AccountLifecycleStatement();
        }
        try {
            return sqlStatementParserEngine.parse(bridgeDorisMaterializedView(com.example.doris.gateway.security.DorisTableProperties.parserView(sql)), useCache);
        } catch (final SQLParsingException | ParseCancellationException originalEx) {
            try {
                return distSQLStatementParserEngine.parse(SQLUtils.trimComment(sql));
            } catch (final SQLParsingException ignored) {
                throw getException(originalEx);
            }
        }
    }

    private static String bridgeDorisMaterializedView(final String sql) {
        Matcher create = CREATE_MATERIALIZED_VIEW.matcher(sql);
        if (create.matches()) {
            return create.group(1) + spaces("MATERIALIZED".length()) + create.group(2)
                    + whitespaceOnly(create.group(3)) + create.group(4);
        }
        Matcher drop = DROP_MATERIALIZED_VIEW.matcher(sql);
        return drop.matches() ? drop.group(1) + spaces("MATERIALIZED".length()) + drop.group(2) : sql;
    }

    private static String whitespaceOnly(final String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) result.append(Character.isWhitespace(value.charAt(i)) ? value.charAt(i) : ' ');
        return result.toString();
    }

    private static String spaces(final int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(' ');
        return result.toString();
    }

    private RuntimeException getException(final RuntimeException originalEx) {
        return originalEx instanceof SQLParsingException
                ? new DialectSQLParsingException(originalEx.getMessage(), ((SQLParsingException) originalEx).getSymbol(), ((SQLParsingException) originalEx).getLine())
                : originalEx;
    }
}
