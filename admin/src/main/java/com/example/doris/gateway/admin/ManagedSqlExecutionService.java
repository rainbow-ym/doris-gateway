package com.example.doris.gateway.admin;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronous fail-closed control plane for SQL which reaches a Doris storage unit.
 * The Proxy calls prepare before JDBC execution and commit/abort from the matching
 * execution callback. Only ACTIVE objects are readable or writable.
 */
@Service
public final class ManagedSqlExecutionService {

    private static final String NAME = "(?:`[^`]+`|[\\p{L}\\p{N}_$]+)";
    private static final String QUALIFIED_NAME = "(" + NAME + "(?:\\." + NAME + ")?)";
    private static final Pattern CREATE = Pattern.compile("(?is)^\\s*CREATE\\s+(MATERIALIZED\\s+VIEW|(?:OR\\s+REPLACE\\s+)?VIEW|TABLE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + QUALIFIED_NAME + ".*$");
    private static final Pattern ALTER = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+" + QUALIFIED_NAME + ".*$");
    private static final Pattern RENAME = Pattern.compile("(?is)^\\s*RENAME\\s+TABLE\\s+" + QUALIFIED_NAME + "\\s+TO\\s+" + QUALIFIED_NAME + ".*$");
    private static final Pattern ALTER_RENAME = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+" + QUALIFIED_NAME + "\\s+RENAME\\s+(?:TO\\s+)?" + QUALIFIED_NAME + ".*$");
    private static final Pattern DROP = Pattern.compile("(?is)^\\s*DROP\\s+(?:MATERIALIZED\\s+)?(?:TABLE|VIEW)\\s+(?:IF\\s+EXISTS\\s+)?" + QUALIFIED_NAME + ".*$");

    private final AdminStore store;
    private final SqlLineageAnalyzer lineage;
    private final DorisMetadataService metadata;
    private final DistSqlService distSql;
    private final byte[] expectedToken;
    @org.springframework.beans.factory.annotation.Autowired
    private AccountAccessService accountAccess;

    public ManagedSqlExecutionService(final AdminStore store, final SqlLineageAnalyzer lineage,
                                      final DorisMetadataService metadata, final DistSqlService distSql,
                                      @Value("${gateway-admin.lineage-token}") final String token) {
        this.store = store;
        this.lineage = lineage;
        this.metadata = metadata;
        this.distSql = distSql;
        expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    public synchronized Map<String, Object> prepare(final String token, final Map<String, Object> request) {
        authenticate(token);
        String storageUnit = required(request, "storageUnit");
        String sql = required(request, "rawSql");
        if (sql.length() > 1024 * 1024) throw new IllegalArgumentException("SQL exceeds 1 MiB.");
        try {
            Map<String, Object> mapping = store.logicalDatabaseByStorageUnit(storageUnit);
            long connectionId = longValue(mapping, "connection_id");
            String logicalDatabase = stringValue(mapping, "logical_name");
            String physicalDatabase = stringValue(mapping, "physical_database");
            String executableSql = SqlTextEnvelope.executableStatement(sql);
            // Replacement changes object identity/lineage; never treat it as IF NOT EXISTS.
            // Keep it closed until versioned view replacement is implemented.
            if (executableSql.matches("(?is)^\\s*CREATE\\s+OR\\s+REPLACE\\s+.*")) {
                throw new IllegalArgumentException("暂不支持安全替换已有视图，请勿使用 CREATE OR REPLACE；不会执行数据库语句。");
            }
            String type = statementType(executableSql);
            if ("BYPASS".equals(type)) return response("", "BYPASS");
            if (!"QUERY".equals(type)) distSql.requireAutomaticBaseline(logicalDatabase);

            SqlLineageAnalyzer.Analysis analysis = lineageAnalysis(type, executableSql, connectionId, physicalDatabase);
            validateReferences(executableSql, type, analysis, connectionId, physicalDatabase);
            if (analysis != null) edgeRows(connectionId, physicalDatabase, analysis, false);
            if ("QUERY".equals(type)) return response("", "QUERY");

            String executionId = UUID.randomUUID().toString();
            String target = analysis == null ? "" : analysis.target.table;
            String renameTarget = analysis == null || analysis.renameTarget == null ? "" : analysis.renameTarget.table;

            if (isCreate(type)) {
                Map<String, Object> existing = store.managedObject(connectionId, physicalDatabase, target);
                if (!existing.isEmpty() && "ACTIVE".equalsIgnoreCase(stringValue(existing, "status"))) {
                    store.saveManagedExecution(executionId, connectionId, storageUnit, logicalDatabase, physicalDatabase,
                            "NOOP", target, "", sql, "PREPARED");
                    return response(executionId, "NOOP");
                }
                store.upsertManagedObject(connectionId, physicalDatabase, target, objectType(type), "PREPARED", executionId, "DDL accepted and awaiting Doris execution.");
            } else if ("ALTER".equals(type) || "DROP".equals(type)) {
                store.setObjectStatus(connectionId, physicalDatabase, target, "SYNCING", executionId, "DDL is executing.");
            } else if ("RENAME".equals(type)) {
                Map<String, Object> renameObject = store.managedObject(connectionId, physicalDatabase, renameTarget);
                if (!renameObject.isEmpty() && !"DROPPED".equals(stringValue(renameObject, "status")) && !"FAILED".equals(stringValue(renameObject, "status"))) {
                    throw notFound(physicalDatabase, renameTarget, "rename target is already registered");
                }
                store.setObjectStatus(connectionId, physicalDatabase, target, "SYNCING", executionId, "Rename is executing.");
                store.upsertManagedObject(connectionId, physicalDatabase, renameTarget, "TABLE", "PREPARED", executionId, "Reserved rename target.");
            } else if ("INSERT_SELECT".equals(type) || "UPDATE".equals(type)) {
                // Keep new readers out until Doris succeeds and inherited protection is published.
                // A rejected write must not change the shared protection policy.
                store.setObjectStatus(connectionId, physicalDatabase, target, "SYNCING", executionId, "正在写入并同步敏感标签，请稍后查询。");
            }
            store.saveManagedExecution(executionId, connectionId, storageUnit, logicalDatabase, physicalDatabase,
                    type, target, renameTarget, sql, "PREPARED");
            return response(executionId, type);
        } catch (final ManagedObjectAccessException ex) {
            throw ex;
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("SQL preflight failed: " + safeMessage(ex), ex);
        }
    }

    public synchronized boolean commit(final String token, final String executionId, final Map<String, Object> incoming) {
        authenticate(token);
        Map<String, Object> execution = store.managedExecution(executionId);
        String status = stringValue(execution, "status");
        if ("COMMITTED".equals(status)) return false;
        if (!"PREPARED".equals(status) && !"SYNCING".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalStateException("Execution cannot be committed from state " + status + '.');
        }
        store.updateManagedExecution(executionId, "SYNCING", "Doris execution succeeded; synchronizing registry and policies.");
        long connectionId = longValue(execution, "connection_id");
        String logicalDatabase = stringValue(execution, "logical_database");
        String physicalDatabase = stringValue(execution, "physical_database");
        String type = stringValue(execution, "statement_type");
        String target = stringValue(execution, "target_table");
        String renameTarget = stringValue(execution, "rename_target_table");
        String sql = stringValue(execution, "raw_sql");
        try {
            List<Map<String, Object>> edges = new ArrayList<>();
            if ("NOOP".equals(type)) {
                // No physical or registry change.
            } else if ("RENAME".equals(type)) {
                if (!metadata.tableExists(connectionId, physicalDatabase, renameTarget)) {
                    throw new IllegalStateException("Renamed object is not visible in Doris metadata: " + renameTarget);
                }
                store.renameTable(connectionId, physicalDatabase, target, renameTarget);
                store.renameManagedObject(connectionId, physicalDatabase, target, renameTarget, executionId);
                distSql.publishManaged(logicalDatabase,type,target,renameTarget,edges);
            } else if ("DROP".equals(type)) {
                if (metadata.tableExists(connectionId, physicalDatabase, target)) {
                    throw new IllegalStateException("Dropped object is still visible in Doris metadata: " + target);
                }
                store.dropTable(connectionId, physicalDatabase, target);
                store.dropManagedObject(connectionId, physicalDatabase, target, executionId);
                distSql.publishManaged(logicalDatabase,type,target,renameTarget,edges);
            } else if ("ALTER".equals(type)) {
                int columns = metadata.scanTable(connectionId, physicalDatabase, target);
                if (columns == 0) throw new IllegalStateException("Altered object is not visible in Doris metadata: " + target);
                store.confirmRecommendationsForTable(connectionId, physicalDatabase, target);
                distSql.publishManaged(logicalDatabase,type,target,renameTarget,edges);
                store.setObjectStatus(connectionId, physicalDatabase, target, "ACTIVE", null, null);
                store.bumpPolicyVersion(connectionId, physicalDatabase, target);
            } else {
                SqlLineageAnalyzer.Analysis analysis = lineageAnalysis(type, sql, connectionId, physicalDatabase);
                if (isCreate(type)) {
                    int columns = metadata.scanTable(connectionId, physicalDatabase, target);
                    boolean asyncMaterializedView = "CREATE_MATERIALIZED_VIEW".equals(type) && columns == 0;
                    if (columns == 0 && !asyncMaterializedView) throw new IllegalStateException("Created object is not visible in Doris metadata: " + target);
                    store.confirmRecommendationsForTable(connectionId, physicalDatabase, target);
                    edges = inheritPolicies(connectionId, physicalDatabase, analysis);
                    distSql.publishManaged(logicalDatabase,type,target,renameTarget,edges);
                    store.setObjectStatus(connectionId, physicalDatabase, target, "ACTIVE", null, null);
                    store.bumpPolicyVersion(connectionId, physicalDatabase, target);
                } else if ("INSERT_SELECT".equals(type) || "UPDATE".equals(type)) {
                    edges = inheritPolicies(connectionId, physicalDatabase, analysis);
                    distSql.publishManaged(logicalDatabase,type,target,renameTarget,edges);
                    store.setObjectStatus(connectionId, physicalDatabase, target, "ACTIVE", null, null);
                    store.bumpPolicyVersion(connectionId, physicalDatabase, target);
                }
            }
            Map<String, Object> event = new LinkedHashMap<>(incoming);
            event.remove("rawSql");
            event.put("eventId", text(incoming, "eventId", executionId));
            event.put("logicalDatabase", logicalDatabase);
            event.put("statementType", type);
            event.put("edges", edges);
            event.put("eventTime", text(incoming, "eventTime", Instant.now().toString()));
            boolean created = store.saveLineageEvent(event);
            if (accountAccess != null) accountAccess.objectChanged(logicalDatabase,type,target,renameTarget);
            store.updateManagedExecution(executionId, "COMMITTED", "全局对象、血缘、保护策略及用户权限映射已同步。");
            return created;
        } catch (final Exception ex) {
            if (isCreate(type)) store.setObjectStatus(connectionId, physicalDatabase, target, "FAILED", executionId, safeMessage(ex));
            else if ("ALTER".equals(type) || "DROP".equals(type) || "RENAME".equals(type) || "INSERT_SELECT".equals(type) || "UPDATE".equals(type)) {
                store.setObjectStatus(connectionId, physicalDatabase, target, "FAILED", executionId, safeMessage(ex));
            }
            store.updateManagedExecution(executionId, "FAILED", safeMessage(ex));
            throw new IllegalStateException("Post-execution synchronization failed; object remains inaccessible: " + safeMessage(ex), ex);
        }
    }

    public synchronized void abort(final String token, final String executionId, final String message) {
        abort(token,executionId,message,false);
    }

    public synchronized void abort(final String token, final String executionId, final String message, final boolean uncertain) {
        authenticate(token);
        Map<String, Object> execution = store.managedExecution(executionId);
        String status = stringValue(execution, "status");
        if (!"PREPARED".equals(status) && !"SYNCING".equals(status)) return;
        long connectionId = longValue(execution, "connection_id");
        String database = stringValue(execution, "physical_database");
        String type = stringValue(execution, "statement_type");
        String target = stringValue(execution, "target_table");
        String renameTarget = stringValue(execution, "rename_target_table");
        if (uncertain) {
            if (!target.isEmpty()) store.setObjectStatus(connectionId,database,target,"FAILED",executionId,"数据库执行结果不确定，等待元数据核查；不会自动重放写入语句。");
            store.updateManagedExecution(executionId,"UNKNOWN","执行结果不确定："+message+"；不会自动推定成功或重放业务 SQL。");
            return;
        }
        if (isCreate(type)) store.setObjectStatus(connectionId, database, target, "FAILED", executionId, message);
        if ("ALTER".equals(type) || "DROP".equals(type) || "RENAME".equals(type) || "INSERT_SELECT".equals(type) || "UPDATE".equals(type)) store.setObjectStatus(connectionId, database, target, "ACTIVE", null, "数据库明确拒绝执行，已恢复原对象状态。");
        if ("RENAME".equals(type) && !renameTarget.isEmpty()) store.setObjectStatus(connectionId, database, renameTarget, "FAILED", executionId, "Rename failed.");
        store.updateManagedExecution(executionId, "ABORTED", message);
    }

    public synchronized Map<String, Object> reconcile(final String executionId) {
        Map<String, Object> execution = store.managedExecution(executionId);
        String status = stringValue(execution, "status");
        if ("PREPARED".equals(status)) {
            // No success callback was durably received. Table existence is not proof
            // that this execution succeeded (another session may create the same name).
            String target = stringValue(execution, "target_table");
            if (!target.isEmpty() && !"NOOP".equals(stringValue(execution, "statement_type"))) {
                store.setObjectStatus(longValue(execution, "connection_id"), stringValue(execution, "physical_database"),
                        target, "FAILED", executionId, "未收到数据库成功确认，执行结果待核实。");
            }
            store.updateManagedExecution(executionId, "UNKNOWN", "未收到数据库成功确认；禁止根据同名对象存在推定成功，需核实执行结果。");
            throw new IllegalStateException("数据库执行结果未知，已保持阻断；不能自动恢复为成功或重放 SQL。");
        }
        if ("UNKNOWN".equals(status)) {
            throw new IllegalStateException("数据库执行结果未知，请先核实真实执行结果；当前入口不会自动推定成功。");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", executionId);
        event.put("eventType", "RECOVERY");
        event.put("sqlDigest", "");
        event.put("eventTime", Instant.now().toString());
        return Collections.<String, Object>singletonMap("created", commitInternal(executionId, event));
    }

    public synchronized Map<String, Object> approveManagedObject(final long objectId) {
        Map<String, Object> object = store.managedObject(objectId);
        long connectionId = longValue(object, "connection_id");
        String database = stringValue(object, "database_name");
        String table = stringValue(object, "table_name");
        String status = stringValue(object, "status");
        if (!"UNMANAGED".equals(status) && !"DRIFTED".equals(status)) {
            throw new IllegalStateException("Only UNMANAGED or DRIFTED objects can be approved; current state is " + status + '.');
        }
        try {
            Map<String, Object> mapping = store.logicalDatabaseFor(connectionId, database);
            String logicalDatabase = stringValue(mapping, "logical_name");
            String storageUnit = stringValue(mapping, "storage_unit");
            store.setObjectStatus(connectionId, database, table, "SYNCING", null, "Administrator approved metadata synchronization.");
            if (metadata.scanTable(connectionId, database, table) == 0) {
                throw new IllegalStateException("批准前未读取到完整表结构，保持阻断，请重新扫描。");
            }
            distSql.synchronizeSingleTable(logicalDatabase, storageUnit, table);
            store.confirmRecommendationsForTable(connectionId, database, table);
            distSql.publishManaged(logicalDatabase,"APPROVE",table,"",Collections.emptyList());
            store.setObjectStatus(connectionId, database, table, "ACTIVE", null, null);
            store.bumpPolicyVersion(connectionId, database, table);
            if (accountAccess != null) accountAccess.project(logicalDatabase);
            store.audit("MANAGED_OBJECT_APPROVED", "objectId=" + objectId + ",database=" + database + ",table=" + table);
            return response(String.valueOf(objectId), "ACTIVE");
        } catch (final Exception ex) {
            store.setObjectStatus(connectionId, database, table, "FAILED", null, safeMessage(ex));
            throw new IllegalStateException("Managed object approval failed; object remains inaccessible: " + safeMessage(ex), ex);
        }
    }

    private boolean commitInternal(final String executionId, final Map<String, Object> event) {
        // Internal reconciliation has already passed authenticated administration.
        return commitBytes(executionId, event);
    }

    private boolean commitBytes(final String executionId, final Map<String, Object> event) {
        String syntheticToken = new String(expectedToken, StandardCharsets.UTF_8);
        return commit(syntheticToken, executionId, event);
    }

    private void validateReferences(final String sql, final String type, final SqlLineageAnalyzer.Analysis analysis,
                                    final long connectionId, final String physicalDatabase) throws Exception {
        Set<SqlLineageAnalyzer.QualifiedName> references = new LinkedHashSet<>();
        if (analysis != null) {
            if (!isCreate(type)) references.add(analysis.target);
            for (SqlLineageAnalyzer.Edge edge : analysis.edges) {
                references.add(new SqlLineageAnalyzer.QualifiedName(edge.source.database, edge.source.table));
            }
            if ("CTAS".equals(type) || "CREATE_VIEW".equals(type) || "CREATE_MATERIALIZED_VIEW".equals(type)
                    || "INSERT_SELECT".equals(type) || "UPDATE".equals(type)) {
                for (String table : lineage.referencedTables(sql)) {
                    SqlLineageAnalyzer.QualifiedName reference = name(table, physicalDatabase);
                    if (isCreate(type) && reference.database.equalsIgnoreCase(analysis.target.database)
                            && reference.table.equalsIgnoreCase(analysis.target.table)) continue;
                    references.add(reference);
                }
            }
        } else {
            Statement statement = CCJSqlParserUtil.parse(SqlLineageAnalyzer.quoteUnicodeIdentifiers(SqlTextEnvelope.executableStatement(sql)));
            for (String table : new TablesNamesFinder().getTableList(statement)) references.add(name(table, physicalDatabase));
        }
        for (SqlLineageAnalyzer.QualifiedName reference : references) {
            if (isSystemDatabase(reference.database)) continue;
            if (!physicalDatabase.equalsIgnoreCase(reference.database)) throw notFound(reference.database, reference.table, "cross-database access is not registered in this logical database");
            if (!store.isActiveManagedObject(connectionId, physicalDatabase, reference.table)) throw notFound(physicalDatabase, reference.table, "object is absent or not ACTIVE");
        }
    }

    private SqlLineageAnalyzer.Analysis lineageAnalysis(final String type, final String sql, final long connectionId,
                                                        final String physicalDatabase) throws Exception {
        String executableSql = SqlTextEnvelope.executableStatement(sql);
        if ("CTAS".equals(type) || "CREATE_VIEW".equals(type) || "CREATE_MATERIALIZED_VIEW".equals(type)
                || "INSERT_SELECT".equals(type) || "UPDATE".equals(type) || "RENAME".equals(type) || "DROP".equals(type)) {
            return lineage.analyze(executableSql, connectionId, physicalDatabase);
        }
        Matcher target = "ALTER".equals(type) ? ALTER.matcher(executableSql) : CREATE.matcher(executableSql);
        if (!target.matches()) return null;
        int group = "ALTER".equals(type) ? 1 : 2;
        return SqlLineageAnalyzer.Analysis.simple(type, name(target.group(group), physicalDatabase));
    }

    private List<Map<String, Object>> inheritPolicies(final long connectionId, final String database,
                                                      final SqlLineageAnalyzer.Analysis analysis) {
        return edgeRows(connectionId, database, analysis, true);
    }

    private List<Map<String, Object>> edgeRows(final long connectionId, final String database,
                                               final SqlLineageAnalyzer.Analysis analysis, final boolean applyPolicy) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (analysis == null) return rows;
        // Validate every edge before modifying any target column.
        if (applyPolicy) edgeRows(connectionId, database, analysis, false);
        Map<String, String> inherited = new LinkedHashMap<>();
        for (SqlLineageAnalyzer.Edge edge : analysis.edges) {
            if (!database.equalsIgnoreCase(edge.source.database) || !database.equalsIgnoreCase(edge.target.database)) {
                throw new ManagedObjectAccessException(database + "." + edge.target.table, "cross-database lineage is not allowed");
            }
            String policy = distSql.effectivePolicy(connectionId, edge.source.database, edge.source.table, edge.source.column);
            if (!policy.isEmpty()) {
                String targetPolicy = distSql.effectivePolicy(connectionId, edge.target.database, edge.target.table, edge.targetColumn);
                if (!targetPolicy.isEmpty() && !targetPolicy.equals(policy)) {
                    throw new IllegalStateException("来源策略与目标字段已有保护冲突，已拒绝自动覆盖：" + edge.target.table + '.' + edge.targetColumn);
                }
                String previous = inherited.put(edge.targetColumn.toLowerCase(Locale.ROOT), policy);
                if (previous != null && !previous.equals(policy)) throw new IllegalStateException("Conflicting sensitive policies feed target column: " + edge.targetColumn);
                if (applyPolicy && !store.inheritPolicyIfUnedited(connectionId, edge.target.database, edge.target.table, edge.targetColumn, policy,targetPolicy)) {
                    // INSERT SELECT targets already exist. CREATE targets become visible after metadata.scanTable.
                    if (!"INSERT_SELECT".equals(analysis.statementType)) throw new IllegalStateException("Target column is not visible after DDL: " + edge.target.table + '.' + edge.targetColumn);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceDatabase", edge.source.database);
            row.put("sourceTable", edge.source.table);
            row.put("sourceColumn", edge.source.column);
            row.put("targetDatabase", edge.target.database);
            row.put("targetTable", edge.target.table);
            row.put("targetColumn", edge.targetColumn);
            row.put("transformType", edge.transformType);
            row.put("inheritedPolicy", policy);
            rows.add(row);
        }
        return rows;
    }

    static String statementType(final String sql) {
        String executableSql = SqlTextEnvelope.executableStatement(sql);
        String normalized = executableSql.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        // System objects are exempted individually in validateReferences. A literal,
        // comment or join mentioning information_schema must not bypass the statement.
        if (normalized.startsWith("SHOW ")) return "BYPASS";
        try {
            Statement statement = CCJSqlParserUtil.parse(SqlLineageAnalyzer.quoteUnicodeIdentifiers(executableSql));
            if (statement instanceof CreateTable) return ((CreateTable) statement).getSelect() == null ? "CREATE_TABLE" : "CTAS";
            if (statement instanceof CreateView) return ((CreateView) statement).isMaterialized() ? "CREATE_MATERIALIZED_VIEW" : "CREATE_VIEW";
        } catch (final Exception ignored) {
            // Doris-specific DDL not understood by JSQLParser is handled by the narrow fallbacks below.
        }
        if (normalized.matches("^CREATE TABLE\\b.*\\bAS SELECT\\b.*")) return "CTAS";
        if (normalized.matches("^CREATE MATERIALIZED VIEW\\b.*")) return "CREATE_MATERIALIZED_VIEW";
        if (normalized.matches("^CREATE (OR REPLACE )?VIEW\\b.*")) return "CREATE_VIEW";
        if (normalized.matches("^CREATE TABLE\\b.*")) return "CREATE_TABLE";
        if (normalized.matches("^INSERT INTO\\b.*\\bSELECT\\b.*")) return "INSERT_SELECT";
        if (normalized.matches("^UPDATE\\b.*")) return "UPDATE";
        if (normalized.matches("^(RENAME TABLE|ALTER TABLE\\b.*\\bRENAME)\\b.*")) return "RENAME";
        if (normalized.matches("^DROP (TABLE|VIEW|MATERIALIZED VIEW)\\b.*")) return "DROP";
        if (normalized.matches("^ALTER TABLE\\b.*")) return "ALTER";
        return "QUERY";
    }

    private static boolean isCreate(final String type) {
        return "CREATE_TABLE".equals(type) || "CTAS".equals(type) || "CREATE_VIEW".equals(type) || "CREATE_MATERIALIZED_VIEW".equals(type);
    }

    private static String objectType(final String type) {
        if ("CREATE_VIEW".equals(type)) return "VIEW";
        if ("CREATE_MATERIALIZED_VIEW".equals(type)) return "MATERIALIZED_VIEW";
        return "TABLE";
    }

    private static SqlLineageAnalyzer.QualifiedName name(final String raw, final String defaultDatabase) {
        String value = clean(raw);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? new SqlLineageAnalyzer.QualifiedName(defaultDatabase, value)
                : new SqlLineageAnalyzer.QualifiedName(clean(value.substring(0, dot)), clean(value.substring(dot + 1)));
    }

    private static String clean(final String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("`") && result.endsWith("`")) result = result.substring(1, result.length() - 1);
        return result.replace("`.`", ".").replace("`", "");
    }

    private static boolean isSystemDatabase(final String database) {
        return "information_schema".equalsIgnoreCase(database) || "mysql".equalsIgnoreCase(database);
    }

    private static Map<String, Object> response(final String executionId, final String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("statementType", type);
        return result;
    }

    private void authenticate(final String token) {
        byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) throw new SecurityException("Invalid lineage ingress token.");
    }

    private static ManagedObjectAccessException notFound(final String database, final String table, final String reason) {
        return new ManagedObjectAccessException(database + '.' + table, reason);
    }

    private static String required(final Map<String, Object> values, final String key) {
        String value = text(values, key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required.");
        return value;
    }

    private static String text(final Map<String, Object> values, final String key, final String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        Object value = mapValue(values, key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(final Map<String, Object> values, final String key) {
        return Long.parseLong(stringValue(values, key));
    }

    private static Object mapValue(final Map<String, Object> values, final String key) {
        if (values.containsKey(key)) return values.get(key);
        if (values.containsKey(key.toUpperCase(Locale.ROOT))) return values.get(key.toUpperCase(Locale.ROOT));
        return values.get(key.toLowerCase(Locale.ROOT));
    }

    private static String safeMessage(final Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().replaceAll("(?i)PASSWORD\\s*=\\s*'[^']*'", "PASSWORD='***'");
    }

    public static final class ManagedObjectAccessException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final String objectName;

        ManagedObjectAccessException(final String objectName, final String reason) {
            super("MANAGED_OBJECT_NOT_FOUND:" + objectName + ":" + reason);
            this.objectName = objectName;
        }

        public String getObjectName() {
            return objectName;
        }
    }
}
