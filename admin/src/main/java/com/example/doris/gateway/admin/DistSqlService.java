package com.example.doris.gateway.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class DistSqlService {

    private final AdminStore store;
    private final PolicyCatalog catalog;
    private final ObjectMapper mapper;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    @org.springframework.beans.factory.annotation.Autowired
    PublishedPolicyStore published;
    private final java.util.concurrent.ConcurrentHashMap<String,Object> publicationLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public DistSqlService(final AdminStore store, final PolicyCatalog catalog, final ObjectMapper mapper,
                          @Value("${gateway-admin.proxy.host}") final String host,
                          @Value("${gateway-admin.proxy.port}") final int port,
                          @Value("${gateway-admin.proxy.username}") final String username,
                          @Value("${gateway-admin.proxy.password}") final String password) {
        this.store = store;
        this.catalog = catalog;
        this.mapper = mapper;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public Map<String, Object> preview(final String database) throws Exception {
        List<RuleRow> current = readRules(database);
        List<RuleRow> desired = desired(database);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", database);
        result.put("current", current);
        result.put("desired", desired);
        result.put("statements", statements(current, desired));
        return result;
    }

    public Map<String, Object> initializeLogicalDatabase(final Map<String, Object> request) throws Exception {
        long connectionId = Long.parseLong(required(request, "connectionId"));
        String logicalDatabase = required(request, "logicalDatabase");
        String physicalDatabase = required(request, "physicalDatabase");
        String storageUnit = required(request, "storageUnit");
        requireStorageUnitIdentifier(storageUnit);
        Map<String, Object> source = store.connection(connectionId);
        String jdbcUrl = "jdbc:mysql://" + source.get("host") + ":" + source.get("port") + "/" + physicalDatabase
                + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        List<String> executed = new ArrayList<>();
        try {
            store.saveLogicalDatabase(connectionId, logicalDatabase, physicalDatabase, storageUnit, "SYNCING", "Initializing storage unit, policies and managed-object registry");
            String createDatabase = "CREATE DATABASE IF NOT EXISTS " + identifier(logicalDatabase);
            execute(null, Collections.singletonList(createDatabase));
            executed.add(createDatabase);
            List<Map<String, Object>> existingUnits = storageUnits(logicalDatabase);
            if (!containsStorageUnit(existingUnits, storageUnit)) {
                String register = "REGISTER STORAGE UNIT " + identifier(storageUnit) + " (URL=" + literal(jdbcUrl)
                        + ",USER=" + literal(String.valueOf(source.get("username"))) + ",PASSWORD=" + literal(store.connectionPassword(connectionId))
                        + ",PROPERTIES(\"minPoolSize\"=1,\"maxPoolSize\"=20))";
                execute(logicalDatabase, Collections.singletonList(register));
                executed.add(redactPassword(register));
            } else {
                executed.add("REGISTER STORAGE UNIT " + identifier(storageUnit) + " -- already registered");
            }
            String load = "LOAD SINGLE TABLE " + identifier(storageUnit) + ".*";
            try {
                execute(logicalDatabase, Collections.singletonList(load));
                executed.add(load);
            } catch (final Exception ex) {
                if (!safeMessage(ex).toLowerCase().contains("already")) throw ex;
                executed.add(load + " -- already loaded");
            }
            List<Map<String, Object>> units = storageUnits(logicalDatabase);
            if (!containsStorageUnit(units, storageUnit)) throw new IllegalStateException("Storage unit was not visible after initialization.");
            // Publish the already confirmed policies before exposing any scanned object.
            // A failed publish therefore leaves the registry closed instead of exposing raw data.
            initializePolicies(logicalDatabase);
            store.activateScannedDatabase(connectionId, physicalDatabase);
            store.saveLogicalDatabase(connectionId, logicalDatabase, physicalDatabase, storageUnit, "ACTIVE", "Initialized and verified");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ACTIVE");
            result.put("logicalDatabase", logicalDatabase);
            result.put("storageUnit", storageUnit);
            result.put("statements", executed);
            result.put("storageUnits", units);
            return result;
        } catch (final Exception ex) {
            store.saveLogicalDatabase(connectionId, logicalDatabase, physicalDatabase, storageUnit, "FAILED", safeMessage(ex));
            throw ex;
        }
    }

    public List<Map<String, Object>> storageUnits(final String database) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = proxyConnection(database); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW STORAGE UNITS FROM " + identifier(database))) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                result.add(row);
            }
        }
        return result;
    }

    public void synchronizeSingleTable(final String logicalDatabase, final String storageUnit, final String table) throws Exception {
        String load = "LOAD SINGLE TABLE " + identifier(storageUnit) + "." + identifier(table);
        try {
            execute(logicalDatabase, Collections.singletonList(load));
        } catch (final Exception ex) {
            if (!safeMessage(ex).toLowerCase().contains("already")) throw ex;
        }
        execute(logicalDatabase, Collections.singletonList("REFRESH TABLE METADATA " + identifier(table)
                + " FROM STORAGE UNIT " + identifier(storageUnit)));
    }

    public Map<String, Object> publish(final String requestedDatabase) throws Exception {
        String database = canonicalDatabase(requestedDatabase);
        synchronized (publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT), ignored -> new Object())) {
            published.requireNoPending(database);
            List<Map<String,Object>> columns = store.confirmedForLogicalDatabase(database);
            Map<String,Object> snapshot = new LinkedHashMap<>();
            snapshot.put("columns", columns);
            snapshot.put("globalRules", desired(database, columns));
            snapshot.put("schema", schema(database));
            return applySnapshot(database, snapshot, "PUBLISHED");
        }
    }

    public String effectivePolicy(long connectionId,String physicalDatabase,String table,String column) {
        Map<String,Object> mapping=store.logicalDatabaseFor(connectionId,physicalDatabase);
        return AutomaticPolicyDelta.label(published.active(String.valueOf(mapping.get("logical_name"))),table,column);
    }
    public void requireAutomaticBaseline(String database) {
        if(!(published.active(database).get("schema") instanceof List))
            throw new IllegalStateException("生效快照缺少字段基线，请先手动发布策略；本次写入尚未执行。");
        published.requireNoPending(database);
    }

    public void initializePolicies(String requestedDatabase) throws Exception {
        String database=canonicalDatabase(requestedDatabase);
        synchronized(publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT),ignored->new Object())) {
            if(!published.hasActive(database)) {publish(database);return;}
            Set<String> tables=new LinkedHashSet<>();
            for(Map<String,Object> r:schema(database))tables.add(String.valueOf(r.get("tableName")));
            for(String table:tables)publishManaged(database,"APPROVE",table,"",Collections.emptyList());
        }
    }

    public Map<String,Object> bootstrapPolicies(String requestedDatabase,boolean apply) throws Exception {
        String database=canonicalDatabase(requestedDatabase);
        synchronized(publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT),ignored->new Object())) {
            published.requireNoPending(database);
            if(published.hasActive(database)&&!(published.active(database).get("schema") instanceof List))
                throw new IllegalStateException("已有生效快照缺少字段基线，不能用当前草稿替换；请先核实并显式发布，升级不会代为发布。");
            if(published.hasActive(database)&&published.active(database).get("schema") instanceof List) {
                Map<String,Object> active=published.active(database);
                if(!normalize(readRules(database)).equals(normalize(mapper.convertValue(active.get("globalRules"),new TypeReference<List<RuleRow>>(){}))))
                    throw new IllegalStateException("运行规则与生效快照不一致，升级前必须核实恢复，禁止自动覆盖。");
                return Collections.singletonMap("status","ALREADY_VERIFIED");
            }
            List<Map<String,Object>> columns=store.confirmedForLogicalDatabase(database);
            List<RuleRow> desired=desired(database,columns);
            if(!normalize(readRules(database)).equals(normalize(desired)))
                throw new IllegalStateException("旧版运行规则与已确认配置不一致，不能自动初始化快照；请先复核未发布草稿，升级不替您发布。");
            if(!apply)return Collections.singletonMap("status","READY_TO_BOOTSTRAP");
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("columns",columns);snapshot.put("globalRules",desired);snapshot.put("schema",schema(database));
            return applySnapshot(database,snapshot,"PUBLISHED");
        }
    }

    public Map<String,Object> publishManaged(String requestedDatabase,String type,String table,String renamed,List<Map<String,Object>> edges) throws Exception {
        String database=canonicalDatabase(requestedDatabase);
        synchronized(publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT),ignored->new Object())) {
            published.requireNoPending(database);
            Map<String,Object> mapping=store.anyMapping(database);
            List<Map<String,Object>> fields=new ArrayList<>();
            for(Map<String,Object> r:store.columns(((Number)mapping.get("connection_id")).longValue(),String.valueOf(mapping.get("physical_database")),null))
                if(table.equalsIgnoreCase(String.valueOf(r.get("tableName"))))fields.add(r);
            Map<String,Object> snapshot=AutomaticPolicyDelta.apply(published.active(database),type,table,renamed,fields,edges,catalog,database);
            return applySnapshot(database,snapshot,"PUBLISHED");
        }
    }

    private List<Map<String,Object>> schema(String database) {
        Map<String,Object> mapping=store.anyMapping(database);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> r:store.columns(((Number)mapping.get("connection_id")).longValue(),String.valueOf(mapping.get("physical_database")),null)) {
            Map<String,Object> field=new LinkedHashMap<>();field.put("tableName",r.get("tableName"));field.put("columnName",r.get("columnName"));result.add(field);
        }
        return result;
    }

    private String canonicalDatabase(String requested) {
        for (Map<String,Object> row : store.logicalDatabases()) {
            String name=String.valueOf(row.get("logicalName"));
            if(name.equalsIgnoreCase(requested)) return name;
        }
        throw new IllegalArgumentException("逻辑库尚未登记，不能发布策略："+requested);
    }

    private Map<String,Object> applySnapshot(String database,Map<String,Object> snapshot,String successStatus) throws Exception {
        List<RuleRow> current = readRules(database);
        List<RuleRow> desired = mapper.convertValue(snapshot.get("globalRules"), new TypeReference<List<RuleRow>>() { });
        List<String> sql = statements(current, desired);
        long version = store.saveVersion(database, current, desired, sql, "APPLYING", "正在应用并校验策略，旧生效快照保持不变。");
        published.begin(version,database,snapshot);
        try {
            execute(database, sql);
            List<RuleRow> actual = readRules(database);
            if (!normalize(actual).equals(normalize(desired))) {
                throw new IllegalStateException("Published MASK rules do not match desired state.");
            }
            published.activate(version,successStatus);
        } catch (final Exception ex) {
            String recovery = recover(database, current);
            boolean recovered = false;
            try { recovered = normalize(readRules(database)).equals(normalize(current)); } catch (Exception ignored) { }
            published.failed(version,recovered,safeMessage(ex) + "; " + recovery);
            throw new IllegalStateException(safeMessage(ex) + "; " + recovery, ex);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("statements", sql);
        result.put("status", successStatus);
        result.put("message", "生效策略已切换；草稿配置未修改，后续发布将使用当前草稿。");
        return result;
    }

    public Map<String, Object> rollback(final long versionId) throws Exception {
        Map<String, Object> version = store.version(versionId);
        String database = String.valueOf(version.get("database_name"));
        synchronized (publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT), ignored -> new Object())) {
            published.requireNoPending(database);
            Map<String,Object> target=published.before(versionId);
            if(!schemaKeys(published.active(database)).equals(schemaKeys(target)))
                throw new IllegalStateException("当前库表字段集合与回滚快照不一致，拒绝跨结构变更回滚；请基于当前结构复核并发布策略。");
            return applySnapshot(database, target, "ROLLED_BACK");
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> schemaKeys(Map<String,Object> snapshot) {
        if(!(snapshot.get("schema") instanceof List))throw new IllegalStateException("历史快照缺少字段基线，无法安全回滚。");
        Set<String> keys=new java.util.TreeSet<>();
        for(Map<String,Object> row:(List<Map<String,Object>>)snapshot.get("schema"))
            keys.add((String.valueOf(row.get("tableName"))+'\u0000'+row.get("columnName")).toLowerCase(java.util.Locale.ROOT));
        return keys;
    }

    public Map<String,Object> recoveryPreview(long version) throws Exception {
        String database=String.valueOf(published.publication(version).get("database_name"));
        synchronized(publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT),ignored->new Object())) {
            Map<String,Object> journal=published.publication(version);
            String status=String.valueOf(journal.get("status"));
            if(!Arrays.asList("APPLYING","RECOVERY_REQUIRED","RECOVERING","RECOVERED_ABORTED").contains(status))
                throw new IllegalStateException("该版本不是待恢复发布；成功发布请使用回滚功能。");
            Map<String,Object> active=published.hasActive(database)?published.active(database):Collections.emptyMap();
            List<RuleRow> target=recoveryTarget(version,active);
            List<RuleRow> current=readRules(database);
            Map<String,Object> result=new LinkedHashMap<>();result.put("version",version);result.put("database",database);result.put("status",status);
            result.put("expectedActiveVersion",active.isEmpty()?0:active.get("version"));
            result.put("runtimeMatches",normalize(current).equals(normalize(target)));
            result.put("statementCount",statements(current,target).size());
            result.put("message",active.isEmpty()?"无生效快照：仅恢复本次发布前的 Proxy 规则，查询继续阻断，需重新确认并发布。":"恢复为当前生效快照对应的 Proxy 规则，终止未完成发布；不修改草稿，不恢复业务对象状态，不重放业务 SQL。");
            return result;
        }
    }

    private List<RuleRow> recoveryTarget(long version,Map<String,Object> active) throws Exception {
        return active.isEmpty()?mapper.readValue(String.valueOf(store.version(version).get("before_json")),new TypeReference<List<RuleRow>>(){}):
                mapper.convertValue(active.get("globalRules"),new TypeReference<List<RuleRow>>(){});
    }

    public Map<String,Object> recoverPublication(long version,long expectedActiveVersion,String actor) throws Exception {
        String database=String.valueOf(published.publication(version).get("database_name"));
        synchronized(publicationLocks.computeIfAbsent(database.toLowerCase(java.util.Locale.ROOT),ignored->new Object())) {
            // Idempotent replay after a completed recovery never touches runtime rules.
            if("RECOVERED_ABORTED".equals(published.publication(version).get("status")))
                return Collections.singletonMap("status","RECOVERED_ABORTED");
            Map<String,Object> preview=recoveryPreview(version);
            if(((Number)preview.get("expectedActiveVersion")).longValue()!=expectedActiveVersion)
                throw new IllegalStateException("核实后生效版本已变化，请重新核实恢复目标。");
            Map<String,Object> active=published.hasActive(database)?published.active(database):Collections.emptyMap();
            List<RuleRow> target=recoveryTarget(version,active);
            published.beginRecovery(version,actor);
            try {
                execute(database,statements(readRules(database),target));
                if(!normalize(readRules(database)).equals(normalize(target)))throw new IllegalStateException("恢复后的运行规则与目标不一致。");
                published.finishRecovery(version,expectedActiveVersion,actor);
            } catch(Exception ex) {
                published.recoveryFailed(version,actor);
                throw new IllegalStateException("发布恢复失败，已保持阻断；不会激活候选策略或重放业务 SQL。",ex);
            }
            Map<String,Object> result=new LinkedHashMap<>();result.put("status","RECOVERED_ABORTED");result.put("message",preview.get("message"));return result;
        }
    }

    private List<RuleRow> desired(final String database) {
        return desired(database,store.confirmedForLogicalDatabase(database));
    }

    private List<RuleRow> desired(final String database, final List<Map<String,Object>> columns) {
        List<RuleRow> result = new ArrayList<>();
        for (Map<String, Object> each : columns) {
            String policyId = String.valueOf(each.get("ASSIGNEDPOLICY") == null ? each.get("assignedPolicy") : each.get("ASSIGNEDPOLICY"));
            PolicyCatalog.Policy policy = catalog.get(policyId);
            Map<String, String> props = new LinkedHashMap<>(policy.props);
            if ("OUTPUT_AES_GCM".equals(policy.id)) {
                props.put("key-env", "GATEWAY_OUTPUT_AES_KEY_B64");
                props.put("key-id", "output-key-v1");
                props.put("aad", database + "." + value(each, "tableName") + "." + value(each, "columnName"));
            }
            result.add(new RuleRow(value(each, "tableName"), value(each, "columnName"), policy.algorithmType, props));
        }
        return result;
    }

    List<RuleRow> readRules(final String database) throws Exception {
        List<RuleRow> result = new ArrayList<>();
        try (Connection connection = proxyConnection(database); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW MASK RULES FROM " + identifier(database))) {
            while (rs.next()) {
                result.add(new RuleRow(rs.getString("table"), rs.getString("column"), rs.getString("algorithm_type"), parseProps(rs.getString("algorithm_props"))));
            }
        }
        return result;
    }

    private List<String> statements(final List<RuleRow> current, final List<RuleRow> desired) {
        Map<String, List<RuleRow>> currentByTable = byTable(current);
        Map<String, List<RuleRow>> desiredByTable = byTable(desired);
        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(currentByTable.keySet());
        tables.addAll(desiredByTable.keySet());
        List<String> result = new ArrayList<>();
        for (String table : tables) {
            List<RuleRow> existing = currentByTable.get(table);
            List<RuleRow> wanted = desiredByTable.get(table);
            if (wanted == null || wanted.isEmpty()) {
                result.add("DROP MASK RULE " + identifier(table));
            } else if (existing == null || existing.isEmpty()) {
                result.add("CREATE MASK RULE " + identifier(table) + " (COLUMNS(" + columnDefinitions(wanted) + "))");
            } else if (!normalize(existing).equals(normalize(wanted))) {
                // Some Proxy releases accept ALTER but keep the original algorithm instance.
                // Recreate the table rule so the new type and properties are guaranteed to apply.
                result.add("DROP MASK RULE " + identifier(table));
                result.add("CREATE MASK RULE " + identifier(table) + " (COLUMNS(" + columnDefinitions(wanted) + "))");
            }
        }
        return result;
    }

    void execute(final String database, final List<String> sql) throws Exception {
        try (Connection connection = proxyConnection(database); Statement statement = connection.createStatement()) {
            for (String each : sql) statement.execute(each);
        }
    }

    private String recover(final String database, final List<RuleRow> target) {
        try {
            execute(database, statements(readRules(database), target));
            return normalize(readRules(database)).equals(normalize(target))
                    ? "previous rules restored" : "automatic restore could not be verified";
        } catch (final Exception recoveryError) {
            return "automatic restore failed: " + safeMessage(recoveryError);
        }
    }

    private String columnDefinitions(final List<RuleRow> rows) {
        List<String> definitions = new ArrayList<>();
        for (RuleRow each : rows) {
            StringBuilder definition = new StringBuilder("(NAME=").append(identifier(each.column)).append(",TYPE(NAME=")
                    .append(literal(each.algorithmType));
            if (!each.props.isEmpty()) {
                definition.append(",PROPERTIES(");
                boolean first = true;
                for (Map.Entry<String, String> prop : each.props.entrySet()) {
                    if (!first) definition.append(',');
                    definition.append('"').append(prop.getKey().replace("\"", "\"\"")).append("\"=").append(literal(prop.getValue()));
                    first = false;
                }
                definition.append(')');
            }
            definition.append("))");
            definitions.add(definition.toString());
        }
        return String.join(",", definitions);
    }

    Connection proxyConnection(final String database) throws Exception {
        String encoded = database == null || database.isEmpty() ? "" : URLEncoder.encode(database, StandardCharsets.UTF_8.name()).replace("+", "%20");
        return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + encoded
                + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=60000", username, password);
    }

    String controlUsername() { return username; }

    public void controlSql(String database, String sql) throws Exception {
        try(Connection c=proxyConnection(database); Statement s=c.createStatement()) { s.execute(sql); }
    }

    private static String required(final Map<String, Object> request, final String key) {
        Object raw = request.get(key);
        String result = raw == null ? "" : String.valueOf(raw).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(key + " is required.");
        return result;
    }

    private static void requireStorageUnitIdentifier(final String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("storageUnit must start with a letter and contain only letters, digits or underscore.");
        }
    }

    private static String redactPassword(final String sql) {
        return sql.replaceAll("(?i)PASSWORD='(?:''|[^'])*'", "PASSWORD='***'");
    }

    private static Map<String, List<RuleRow>> byTable(final List<RuleRow> rows) {
        Map<String, List<RuleRow>> result = new LinkedHashMap<>();
        for (RuleRow each : rows) {
            result.computeIfAbsent(each.table, ignored -> new ArrayList<RuleRow>()).add(each);
        }
        return result;
    }

    private Map<String, String> parseProps(final String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyMap();
        try {
            Map<String, Object> decoded = mapper.readValue(value, new TypeReference<Map<String, Object>>() { });
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> each : decoded.entrySet()) {
                result.put(each.getKey(), String.valueOf(each.getValue()));
            }
            return result;
        } catch (final Exception ignored) {
            // Compatibility fallback for older Proxy versions that return key=value pairs.
            Map<String, String> result = new LinkedHashMap<>();
            for (String pair : value.split(",")) {
                int index = pair.indexOf('=');
                if (index > 0) result.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
            }
            return result;
        }
    }

    private static String identifier(final String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private static String literal(final String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String value(final Map<String, Object> row, final String camel) {
        Object value = row.get(camel);
        if (value == null) value = row.get(camel.toUpperCase());
        return String.valueOf(value);
    }

    private static Object valueIgnoreCase(final Map<String, Object> row, final String key) {
        for (Map.Entry<String, Object> each : row.entrySet()) {
            if (key.equalsIgnoreCase(each.getKey())) return each.getValue();
        }
        return null;
    }

    private static boolean containsStorageUnit(final List<Map<String, Object>> units, final String storageUnit) {
        for (Map<String, Object> each : units) {
            Object name = valueIgnoreCase(each, "name");
            if (name != null && storageUnit.equalsIgnoreCase(String.valueOf(name))) return true;
        }
        return false;
    }

    private static String safeMessage(final Exception ex) {
        String value = ex.getMessage();
        return value == null ? ex.getClass().getSimpleName() : value.replaceAll("(?i)password=[^&\\s]+", "password=***");
    }

    private static String normalize(final List<RuleRow> rows) {
        List<String> result = new ArrayList<>();
        for (RuleRow each : rows) {
            result.add(each.table + "|" + each.column + "|" + each.algorithmType + "|" + new TreeMap<>(each.props));
        }
        Collections.sort(result);
        return result.toString();
    }

    public static final class RuleRow {
        public String table;
        public String column;
        public String algorithmType;
        public Map<String, String> props = new LinkedHashMap<>();

        public RuleRow() { }

        RuleRow(final String table, final String column, final String algorithmType, final Map<String, String> props) {
            this.table = table;
            this.column = column;
            this.algorithmType = algorithmType;
            this.props = props;
        }
    }
}
