package com.example.doris.gateway.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
public class AdminStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretCodec secrets;

    public AdminStore(final JdbcTemplate jdbc, final ObjectMapper mapper, final SecretCodec secrets) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.secrets = secrets;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS connections (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) NOT NULL, host VARCHAR(255) NOT NULL, port INT NOT NULL, username VARCHAR(128) NOT NULL, password_enc VARCHAR(2048) NOT NULL, database_pattern VARCHAR(255) NOT NULL DEFAULT '%', table_pattern VARCHAR(255) NOT NULL DEFAULT '%', created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS metadata_columns (id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT NOT NULL, database_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL, column_name VARCHAR(255) NOT NULL, data_type VARCHAR(128), column_comment VARCHAR(2048), recommended_policy VARCHAR(64), assigned_policy VARCHAR(64), status VARCHAR(32) NOT NULL DEFAULT 'UNCONFIRMED', updated_at VARCHAR(64) NOT NULL, UNIQUE(connection_id,database_name,table_name,column_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS publish_versions (id BIGINT AUTO_INCREMENT PRIMARY KEY, database_name VARCHAR(255) NOT NULL, before_json CLOB NOT NULL, desired_json CLOB NOT NULL, statements_json CLOB NOT NULL, status VARCHAR(32) NOT NULL, message CLOB, created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, action VARCHAR(64) NOT NULL, detail CLOB, created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS logical_databases (id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT NOT NULL, logical_name VARCHAR(255) NOT NULL UNIQUE, physical_database VARCHAR(255) NOT NULL, storage_unit VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, message CLOB, updated_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS lineage_events (id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(64) NOT NULL UNIQUE, event_type VARCHAR(32) NOT NULL, logical_database VARCHAR(255), statement_type VARCHAR(64), sql_digest VARCHAR(128), payload CLOB NOT NULL, created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS lineage_edges (id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(64) NOT NULL, source_database VARCHAR(255), source_table VARCHAR(255), source_column VARCHAR(255), target_database VARCHAR(255), target_table VARCHAR(255), target_column VARCHAR(255), transform_type VARCHAR(64), inherited_policy VARCHAR(64), created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managed_objects (id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT NOT NULL, database_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL, object_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, schema_fingerprint VARCHAR(128), policy_version BIGINT NOT NULL DEFAULT 0, pending_execution_id VARCHAR(64), last_error CLOB, updated_at VARCHAR(64) NOT NULL, UNIQUE(connection_id,database_name,table_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managed_columns (id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT NOT NULL, database_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL, column_name VARCHAR(255) NOT NULL, ordinal_position INT NOT NULL, data_type VARCHAR(128), status VARCHAR(32) NOT NULL, updated_at VARCHAR(64) NOT NULL, UNIQUE(connection_id,database_name,table_name,column_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managed_sql_executions (id BIGINT AUTO_INCREMENT PRIMARY KEY, execution_id VARCHAR(64) NOT NULL UNIQUE, connection_id BIGINT NOT NULL, storage_unit VARCHAR(128) NOT NULL, logical_database VARCHAR(255) NOT NULL, physical_database VARCHAR(255) NOT NULL, statement_type VARCHAR(64) NOT NULL, target_table VARCHAR(255), rename_target_table VARCHAR(255), raw_sql CLOB NOT NULL, status VARCHAR(32) NOT NULL, message CLOB, created_at VARCHAR(64) NOT NULL, updated_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managed_registry_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)");
        migrateExistingManagedMetadata();
        jdbc.execute("CREATE TABLE IF NOT EXISTS onboarding_jobs (id VARCHAR(64) PRIMARY KEY, connection_id BIGINT NOT NULL, database_name VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL, stage VARCHAR(64), message CLOB, updated_at VARCHAR(64) NOT NULL)");
        jdbc.update("UPDATE onboarding_jobs SET status='FAILED',message='服务重启中断了接入，请点击重试；已完成的步骤将自动复用。' WHERE status IN ('QUEUED','RUNNING')");
    }

    private void migrateExistingManagedMetadata() {
        Integer migrated = jdbc.queryForObject("SELECT COUNT(*) FROM managed_registry_meta WHERE meta_key='legacy_metadata_migrated'", Integer.class);
        if (migrated != null && migrated > 0) return;
        List<Map<String, Object>> mappings = jdbc.queryForList("SELECT connection_id,physical_database FROM logical_databases WHERE status='ACTIVE'");
        for (Map<String, Object> mapping : mappings) {
            long connectionId = Long.parseLong(String.valueOf(value(mapping, "connection_id")));
            String database = String.valueOf(value(mapping, "physical_database"));
            List<String> tables = jdbc.queryForList("SELECT DISTINCT table_name FROM metadata_columns WHERE connection_id=? AND database_name=?", String.class, connectionId, database);
            for (String table : tables) {
                upsertManagedObject(connectionId, database, table, "TABLE", "ACTIVE", null, "Migrated from the existing gateway metadata.");
                copyMetadataColumnsToRegistry(connectionId, database, table, "ACTIVE");
            }
        }
        jdbc.update("INSERT INTO managed_registry_meta(meta_key,meta_value) VALUES('legacy_metadata_migrated',?)", Instant.now().toString());
    }

    public synchronized long saveConnection(final Map<String, Object> request) {
        validateConnection(request, -1);
        String password = required(request, "password");
        jdbc.update("INSERT INTO connections(name,host,port,username,password_enc,database_pattern,table_pattern,created_at) VALUES(?,?,?,?,?,?,?,?)",
                required(request, "name").trim(), required(request, "host"), integer(request, "port", 9030), required(request, "username"), secrets.encrypt(password),
                text(request, "databasePattern", "%"), text(request, "tablePattern", "%"), Instant.now().toString());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM connections", Long.class);
        audit("CONNECTION_CREATED", "connectionId=" + id + ",name=" + required(request, "name"));
        return id == null ? 0 : id;
    }

    private void validateConnection(final Map<String, Object> request, final long id) {
        String name = required(request, "name").trim();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM connections WHERE LOWER(TRIM(name))=LOWER(?) AND id<>?", Integer.class, name, id) > 0)
            throw new IllegalArgumentException("连接名称已存在，请编辑原连接或使用不同名称。");
        int port = integer(request, "port", 9030);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在 1—65535 之间。");
        String user = required(request, "username");
        com.example.doris.gateway.account.AccountName.validate(user);
        String host = required(request, "host");
        if (!host.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("请输入有效的主机名或 IP 地址。");
        if (id < 0 && jdbc.queryForObject("SELECT COUNT(*) FROM connections WHERE LOWER(host)=LOWER(?) AND port=? AND username=? AND database_pattern=? AND table_pattern=?", Integer.class,
                host, port, user, text(request,"databasePattern","%"), text(request,"tablePattern","%")) > 0)
            throw new IllegalArgumentException("相同地址、账号和扫描范围的连接已存在，请复用或编辑原连接。");
        String pass = text(request, "password", "");
        if (pass.isEmpty() && id > 0) pass = connectionPassword(id);
        for (Map<String,Object> other : connections()) {
            long otherId = ((Number) other.get("id")).longValue();
            if (otherId != id && user.equalsIgnoreCase(String.valueOf(other.get("username"))) && !user.equals(other.get("username")))
                throw new IllegalArgumentException("网关已有仅大小写不同的用户名，请使用一致的用户名，避免身份冲突。");
            if (otherId != id && user.equals(other.get("username")) && !pass.equals(connectionPassword(otherId)))
                throw new IllegalArgumentException("网关已有同名账号但密码不同。一个网关用户名只能对应一个密码，请使用一致凭据或不同用户名。");
        }
    }

    public synchronized void updateConnection(final long id, final Map<String,Object> request) {
        Map<String,Object> old = connection(id);
        validateConnection(request, id);
        String pass = text(request,"password","");
        boolean credentialChange = !required(request,"host").equals(old.get("host")) || integer(request,"port",9030) != ((Number)old.get("port")).intValue()
                || !required(request,"username").equals(old.get("username")) || (!pass.isEmpty() && !pass.equals(connectionPassword(id)));
        if (credentialChange && dependencyCount(id) > 0) throw new IllegalStateException("该连接已关联逻辑库，不能直接替换连接身份。请保留原凭据，或新增不同名称和账号的连接。");
        jdbc.update("UPDATE connections SET name=?,host=?,port=?,username=?,password_enc=?,database_pattern=?,table_pattern=? WHERE id=?",
                required(request,"name").trim(),required(request,"host"),integer(request,"port",9030),required(request,"username"),pass.isEmpty()?old.get("password_enc"):secrets.encrypt(pass),
                text(request,"databasePattern","%"),text(request,"tablePattern","%"),id);
        audit("CONNECTION_UPDATED", "connectionId="+id);
    }

    public int dependencyCount(final long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM logical_databases WHERE connection_id=?", Integer.class,id)
                + jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_jobs WHERE connection_id=? AND status IN ('QUEUED','RUNNING')", Integer.class,id);
    }

    @org.springframework.transaction.annotation.Transactional
    public synchronized void deleteConnection(final long id) {
        connection(id);
        if (dependencyCount(id)>0) throw new IllegalStateException("连接仍关联逻辑库或接入任务，不能删除。请先在接入列表中解除对应逻辑库映射；不会删除 Doris 真实库表。");
        jdbc.update("DELETE FROM managed_columns WHERE connection_id=?",id);
        jdbc.update("DELETE FROM managed_objects WHERE connection_id=?",id);
        jdbc.update("DELETE FROM metadata_columns WHERE connection_id=?",id);
        jdbc.update("DELETE FROM connections WHERE id=?",id);
        audit("CONNECTION_DELETED","connectionId="+id);
    }

    public List<Map<String,Object>> jobs() {
        return jdbc.queryForList("SELECT id,connection_id AS connectionId,database_name AS databaseName,status,stage,message,updated_at AS updatedAt FROM onboarding_jobs ORDER BY updated_at DESC LIMIT 300");
    }
    public void newJob(String id,long connectionId,String database) {
        jdbc.update("INSERT INTO onboarding_jobs VALUES(?,?,?,'QUEUED','等待执行','',?)",id,connectionId,database,Instant.now().toString());
    }
    public void jobProgress(String id,String status,String stage,String message) {
        jdbc.update("UPDATE onboarding_jobs SET status=?,stage=?,message=?,updated_at=? WHERE id=?",status,stage,message,Instant.now().toString(),id);
    }
    public Map<String,Object> anyMapping(String name) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM logical_databases WHERE logical_name=?",name);
        return rows.isEmpty()?Collections.emptyMap():rows.get(0);
    }
    public void removeMapping(String name) {
        Map<String,Object> m=anyMapping(name);
        if(m.isEmpty()) return;
        jdbc.update("UPDATE managed_objects SET status='UNMANAGED',last_error='逻辑库已解除接入' WHERE connection_id=? AND database_name=?",m.get("connection_id"),m.get("physical_database"));
        jdbc.update("DELETE FROM logical_databases WHERE logical_name=?",name);
        audit("DATABASE_DETACHED",name);
    }

    public List<Map<String, Object>> connections() {
        return jdbc.queryForList("SELECT id,name,host,port,username,database_pattern AS databasePattern,table_pattern AS tablePattern,created_at AS createdAt FROM connections ORDER BY id");
    }

    public Map<String, Object> connection(final long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM connections WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Connection not found: " + id);
        }
        return rows.get(0);
    }

    public String connectionPassword(final long id) {
        return secrets.decrypt(String.valueOf(connection(id).get("password_enc")));
    }

    public void upsertColumn(final long connectionId, final String database, final String table, final String column,
                             final String type, final String comment, final String recommendation) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=?",
                Integer.class, connectionId, database, table, column);
        if (existing != null && existing > 0) {
            jdbc.update("UPDATE metadata_columns SET data_type=?,column_comment=?,recommended_policy=?,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=?",
                    type, comment, recommendation, Instant.now().toString(), connectionId, database, table, column);
        } else {
            jdbc.update("INSERT INTO metadata_columns(connection_id,database_name,table_name,column_name,data_type,column_comment,recommended_policy,assigned_policy,status,updated_at) VALUES(?,?,?,?,?,?,?,NULL,'UNCONFIRMED',?)",
                    connectionId, database, table, column, type, comment, recommendation, Instant.now().toString());
        }
    }

    public void discoverManagedObject(final long connectionId, final String database, final String table, final String objectType) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM managed_objects WHERE connection_id=? AND database_name=? AND table_name=?",
                Integer.class, connectionId, database, table);
        if (count == null || count == 0) {
            upsertManagedObject(connectionId, database, table, objectType, "UNMANAGED", null, "Discovered by metadata scan; approval or gateway initialization is required.");
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void replaceManagedColumns(final long connectionId, final String database, final String table,
                                      final List<ManagedColumn> columns, final String status) {
        jdbc.update("DELETE FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=?", connectionId, database, table);
        int ordinal = 0;
        for (ManagedColumn column : columns) {
            jdbc.update("INSERT INTO managed_columns(connection_id,database_name,table_name,column_name,ordinal_position,data_type,status,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    connectionId, database, table, column.name, ++ordinal, column.dataType, status, Instant.now().toString());
        }
    }

    /** Commit a complete physical table snapshot, never a partially fetched result set. */
    @org.springframework.transaction.annotation.Transactional
    public void replaceTableMetadata(final long connectionId, final String database, final String table,
                                     final List<ScannedColumn> columns) {
        if (columns.isEmpty()) throw new IllegalArgumentException("空元数据快照不能覆盖已有表，请核实表是否存在。");
        List<ManagedColumn> ordered = new ArrayList<>();
        for (ScannedColumn column : columns) {
            upsertColumn(connectionId, database, table, column.name, column.type, column.comment, column.recommendation);
            ordered.add(new ManagedColumn(column.name, column.type));
        }
        replaceManagedColumns(connectionId, database, table, ordered, "SYNCING");
        jdbc.update("DELETE FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? AND column_name NOT IN (SELECT column_name FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=?)",
                connectionId, database, table, connectionId, database, table);
        // Exact exceptions must not silently survive a dropped column and attach to
        // a different future column of the same name. Type-wide rules are preserved.
        jdbc.update("DELETE FROM user_output_policies WHERE logical_name IN (SELECT logical_name FROM logical_databases WHERE connection_id=? AND physical_database=?) AND table_name=? AND column_name<>'*' AND column_name NOT IN (SELECT column_name FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=?)",
                connectionId, database, table, connectionId, database, table);
    }

    public static final class ScannedColumn {
        public final String name, type, comment, recommendation;
        public ScannedColumn(String name, String type, String comment, String recommendation) {
            this.name=name;this.type=type;this.comment=comment;this.recommendation=recommendation;
        }
    }

    public void copyMetadataColumnsToRegistry(final long connectionId, final String database, final String table, final String status) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT column_name,data_type FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? ORDER BY id",
                connectionId, database, table);
        List<ManagedColumn> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) columns.add(new ManagedColumn(String.valueOf(value(row, "column_name")), String.valueOf(value(row, "data_type"))));
        replaceManagedColumns(connectionId, database, table, columns, status);
    }

    public void activateScannedDatabase(final long connectionId, final String database) {
        List<String> tables = jdbc.queryForList("SELECT DISTINCT table_name FROM metadata_columns WHERE connection_id=? AND database_name=?", String.class, connectionId, database);
        for (String table : tables) {
            upsertManagedObject(connectionId, database, table, "TABLE", "ACTIVE", null, "Activated during logical database initialization.");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=?",
                    Integer.class, connectionId, database, table) == 0) {
                copyMetadataColumnsToRegistry(connectionId, database, table, "ACTIVE");
            } else {
                jdbc.update("UPDATE managed_columns SET status='ACTIVE' WHERE connection_id=? AND database_name=? AND table_name=?", connectionId, database, table);
            }
        }
        audit("MANAGED_DATABASE_ACTIVATED", "connectionId=" + connectionId + ",database=" + database + ",tables=" + tables.size());
    }

    public void upsertManagedObject(final long connectionId, final String database, final String table, final String objectType,
                                    final String status, final String executionId, final String message) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM managed_objects WHERE connection_id=? AND database_name=? AND table_name=?",
                Integer.class, connectionId, database, table);
        String fingerprint = schemaFingerprint(connectionId, database, table);
        if (count != null && count > 0) {
            jdbc.update("UPDATE managed_objects SET object_type=?,status=?,schema_fingerprint=?,pending_execution_id=?,last_error=?,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                    objectType, status, fingerprint, executionId, message, Instant.now().toString(), connectionId, database, table);
        } else {
            jdbc.update("INSERT INTO managed_objects(connection_id,database_name,table_name,object_type,status,schema_fingerprint,policy_version,pending_execution_id,last_error,updated_at) VALUES(?,?,?,?,?,?,0,?,?,?)",
                    connectionId, database, table, objectType, status, fingerprint, executionId, message, Instant.now().toString());
        }
    }

    public Map<String, Object> managedObject(final long connectionId, final String database, final String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM managed_objects WHERE connection_id=? AND LOWER(database_name)=LOWER(?) AND LOWER(table_name)=LOWER(?)",
                connectionId, database, table);
        return rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
    }

    public Map<String, Object> managedObject(final long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM managed_objects WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Managed object not found: " + id);
        return rows.get(0);
    }

    public boolean isActiveManagedObject(final long connectionId, final String database, final String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM managed_objects WHERE connection_id=? AND LOWER(database_name)=LOWER(?) AND LOWER(table_name)=LOWER(?) AND status='ACTIVE'",
                Integer.class, connectionId, database, table);
        return count != null && count == 1;
    }

    public List<String> activeManagedColumns(final long connectionId, final String database, final String table) {
        return jdbc.queryForList("SELECT column_name FROM managed_columns WHERE connection_id=? AND LOWER(database_name)=LOWER(?) AND LOWER(table_name)=LOWER(?) AND status='ACTIVE' ORDER BY ordinal_position",
                String.class, connectionId, database, table);
    }

    public List<Map<String, Object>> managedObjects() {
        return jdbc.queryForList("SELECT id,connection_id AS connectionId,database_name AS databaseName,table_name AS tableName,object_type AS objectType,status,schema_fingerprint AS schemaFingerprint,policy_version AS policyVersion,pending_execution_id AS pendingExecutionId,last_error AS lastError,updated_at AS updatedAt FROM managed_objects ORDER BY database_name,table_name");
    }

    public Map<String, Object> logicalDatabaseFor(final long connectionId, final String physicalDatabase) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM logical_databases WHERE connection_id=? AND LOWER(physical_database)=LOWER(?) AND status='ACTIVE' ORDER BY id DESC",
                connectionId, physicalDatabase);
        if (rows.isEmpty()) throw new IllegalStateException("No active logical database maps connection " + connectionId + " and physical database " + physicalDatabase + '.');
        return rows.get(0);
    }

    public void saveManagedExecution(final String executionId, final long connectionId, final String storageUnit,
                                     final String logicalDatabase, final String physicalDatabase, final String statementType,
                                     final String targetTable, final String renameTargetTable, final String sql, final String status) {
        jdbc.update("INSERT INTO managed_sql_executions(execution_id,connection_id,storage_unit,logical_database,physical_database,statement_type,target_table,rename_target_table,raw_sql,status,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                executionId, connectionId, storageUnit, logicalDatabase, physicalDatabase, statementType, targetTable, renameTargetTable,
                sql, status, "", Instant.now().toString(), Instant.now().toString());
    }

    public Map<String, Object> managedExecution(final String executionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM managed_sql_executions WHERE execution_id=?", executionId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Managed SQL execution not found: " + executionId);
        return rows.get(0);
    }

    public void updateManagedExecution(final String executionId, final String status, final String message) {
        jdbc.update("UPDATE managed_sql_executions SET status=?,message=?,updated_at=? WHERE execution_id=?", status, message, Instant.now().toString(), executionId);
    }

    public List<Map<String, Object>> pendingManagedExecutions() {
        return jdbc.queryForList("SELECT * FROM managed_sql_executions WHERE status IN ('PREPARED','SYNCING','FAILED') ORDER BY id");
    }

    public List<Map<String, Object>> managedExecutions() {
        return jdbc.queryForList("SELECT execution_id AS executionId,logical_database AS logicalDatabase,physical_database AS physicalDatabase,statement_type AS statementType,target_table AS targetTable,status,message,created_at AS createdAt,updated_at AS updatedAt FROM managed_sql_executions ORDER BY id DESC LIMIT 200");
    }

    public void setObjectStatus(final long connectionId, final String database, final String table, final String status,
                                final String executionId, final String message) {
        Map<String, Object> current = managedObject(connectionId, database, table);
        String objectType = current.isEmpty() ? "TABLE" : String.valueOf(value(current, "object_type"));
        upsertManagedObject(connectionId, database, table, objectType, status, executionId, message);
        jdbc.update("UPDATE managed_columns SET status=?,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                status, Instant.now().toString(), connectionId, database, table);
    }

    public void renameManagedObject(final long connectionId, final String database, final String oldTable, final String newTable,
                                    final String executionId) {
        jdbc.update("DELETE FROM managed_objects WHERE connection_id=? AND database_name=? AND table_name=?", connectionId, database, newTable);
        jdbc.update("DELETE FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=?", connectionId, database, newTable);
        jdbc.update("UPDATE managed_objects SET table_name=?,status='ACTIVE',pending_execution_id=NULL,last_error=NULL,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                newTable, Instant.now().toString(), connectionId, database, oldTable);
        jdbc.update("UPDATE managed_columns SET table_name=?,status='ACTIVE',updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                newTable, Instant.now().toString(), connectionId, database, oldTable);
        audit("MANAGED_OBJECT_RENAMED", "database=" + database + ",oldTable=" + oldTable + ",newTable=" + newTable + ",executionId=" + executionId);
    }

    public void dropManagedObject(final long connectionId, final String database, final String table, final String executionId) {
        setObjectStatus(connectionId, database, table, "DROPPED", null, "Dropped by execution " + executionId);
        audit("MANAGED_OBJECT_DROPPED", "database=" + database + ",table=" + table + ",executionId=" + executionId);
    }

    public void bumpPolicyVersion(final long connectionId, final String database, final String table) {
        jdbc.update("UPDATE managed_objects SET policy_version=policy_version+1,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                Instant.now().toString(), connectionId, database, table);
    }

    private String schemaFingerprint(final long connectionId, final String database, final String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT column_name,data_type FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? ORDER BY id",
                connectionId, database, table);
        StringBuilder value = new StringBuilder();
        for (Map<String, Object> row : rows) value.append(row.get("column_name")).append(':').append(row.get("data_type")).append(';');
        return Integer.toHexString(value.toString().hashCode());
    }

    private static Object value(final Map<String, Object> row, final String key) {
        if (row.containsKey(key)) return row.get(key);
        if (row.containsKey(key.toUpperCase())) return row.get(key.toUpperCase());
        return row.get(key.toLowerCase());
    }

    public static final class ManagedColumn {
        public final String name;
        public final String dataType;

        public ManagedColumn(final String name, final String dataType) {
            this.name = name;
            this.dataType = dataType;
        }
    }

    public List<Map<String, Object>> columns(final long connectionId, final String database, final String query) {
        StringBuilder sql = new StringBuilder("SELECT id,connection_id AS connectionId,database_name AS databaseName,table_name AS tableName,column_name AS columnName,data_type AS dataType,column_comment AS columnComment,recommended_policy AS recommendedPolicy,assigned_policy AS assignedPolicy,status FROM metadata_columns WHERE connection_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(connectionId);
        if (database != null && !database.isEmpty()) {
            sql.append(" AND database_name=?");
            args.add(database);
        }
        if (query != null && !query.isEmpty()) {
            sql.append(" AND (LOWER(table_name) LIKE ? OR LOWER(column_name) LIKE ? OR LOWER(column_comment) LIKE ?)");
            String pattern = "%" + query.toLowerCase() + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        sql.append(" ORDER BY database_name,table_name,id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public int assign(final List<Long> ids, final String policyId) {
        int changed = 0;
        for (Long id : ids) {
            changed += jdbc.update("UPDATE metadata_columns SET assigned_policy=?,status='CONFIRMED',updated_at=? WHERE id=?", policyId, Instant.now().toString(), id);
        }
        audit("POLICY_BATCH_ASSIGNED", "policy=" + policyId + ",count=" + changed);
        return changed;
    }

    public int confirmRecommendations(final long connectionId) {
        int changed = jdbc.update("UPDATE metadata_columns SET assigned_policy=recommended_policy,status='CONFIRMED',updated_at=? WHERE connection_id=? AND status='UNCONFIRMED' AND recommended_policy IS NOT NULL AND recommended_policy<>'NONE'", Instant.now().toString(), connectionId);
        audit("RECOMMENDATIONS_CONFIRMED", "connectionId=" + connectionId + ",count=" + changed);
        return changed;
    }

    public int confirmRecommendationsForTable(final long connectionId, final String database, final String table) {
        int changed = jdbc.update("UPDATE metadata_columns SET assigned_policy=recommended_policy,status='CONFIRMED',updated_at=? WHERE connection_id=? AND database_name=? AND table_name=? AND status='UNCONFIRMED' AND recommended_policy IS NOT NULL AND recommended_policy<>'NONE'",
                Instant.now().toString(), connectionId, database, table);
        if (changed > 0) audit("DDL_RECOMMENDATIONS_AUTO_CONFIRMED", "connectionId=" + connectionId + ",database=" + database + ",table=" + table + ",count=" + changed);
        return changed;
    }

    public List<Map<String, Object>> confirmedForDatabase(final String database) {
        return jdbc.queryForList("SELECT database_name AS databaseName,table_name AS tableName,column_name AS columnName,assigned_policy AS assignedPolicy FROM metadata_columns WHERE database_name=? AND status='CONFIRMED' AND assigned_policy IS NOT NULL AND assigned_policy<>'NONE' ORDER BY table_name,column_name", database);
    }

    public long saveVersion(final String database, final Object before, final Object desired, final Object statements, final String status, final String message) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("INSERT INTO publish_versions(database_name,before_json,desired_json,statements_json,status,message,created_at) VALUES(?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] values = {database, json(before), json(desired), json(statements), status, message, Instant.now().toString()};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Long id = keys.getKey().longValue();
        audit("RULES_" + status, "database=" + database + ",version=" + id);
        return id == null ? 0 : id;
    }

    public List<Map<String, Object>> versions() {
        return jdbc.queryForList("SELECT id,database_name AS databaseName,status,message,created_at AS createdAt FROM publish_versions ORDER BY id DESC LIMIT 100");
    }

    public Map<String, Object> version(final long id) {
        return jdbc.queryForMap("SELECT * FROM publish_versions WHERE id=?", id);
    }

    public List<Map<String, Object>> audits() {
        return jdbc.queryForList("SELECT id,action,detail,created_at AS createdAt FROM audit_log ORDER BY id DESC LIMIT 200");
    }

    public void saveLogicalDatabase(final long connectionId, final String logicalName, final String physicalDatabase,
                                    final String storageUnit, final String status, final String message) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM logical_databases WHERE logical_name=?", Integer.class, logicalName);
        if (count != null && count > 0) {
            jdbc.update("UPDATE logical_databases SET connection_id=?,physical_database=?,storage_unit=?,status=?,message=?,updated_at=? WHERE logical_name=?",
                    connectionId, physicalDatabase, storageUnit, status, message, Instant.now().toString(), logicalName);
        } else {
            jdbc.update("INSERT INTO logical_databases(connection_id,logical_name,physical_database,storage_unit,status,message,updated_at) VALUES(?,?,?,?,?,?,?)",
                    connectionId, logicalName, physicalDatabase, storageUnit, status, message, Instant.now().toString());
        }
        audit("LOGICAL_DATABASE_" + status, "logicalDatabase=" + logicalName + ",physicalDatabase=" + physicalDatabase + ",storageUnit=" + storageUnit);
    }

    public List<Map<String, Object>> logicalDatabases() {
        return jdbc.queryForList("SELECT id,connection_id AS connectionId,logical_name AS logicalName,physical_database AS physicalDatabase,storage_unit AS storageUnit,status,message,updated_at AS updatedAt FROM logical_databases ORDER BY logical_name");
    }

    public Map<String, Object> logicalDatabaseByStorageUnit(final String storageUnit) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM logical_databases WHERE storage_unit=? AND status='ACTIVE'", storageUnit);
        if (rows.size() != 1) {
            throw new IllegalStateException("Expected one active logical database for storage unit: " + storageUnit);
        }
        return rows.get(0);
    }

    public Map<String, Object> logicalDatabase(final String logicalName) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM logical_databases WHERE logical_name=? AND status='ACTIVE'", logicalName);
        return rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
    }

    public List<Map<String, Object>> confirmedForLogicalDatabase(final String logicalDatabase) {
        List<Map<String, Object>> mappings = jdbc.queryForList("SELECT * FROM logical_databases WHERE logical_name=? ORDER BY id DESC", logicalDatabase);
        Map<String, Object> mapping = mappings.isEmpty() ? Collections.<String, Object>emptyMap() : mappings.get(0);
        String physicalDatabase = mapping.isEmpty() ? logicalDatabase : String.valueOf(mapping.get("physical_database"));
        if (mapping.isEmpty()) return Collections.emptyList();
        return jdbc.queryForList("SELECT database_name AS databaseName,table_name AS tableName,column_name AS columnName,assigned_policy AS assignedPolicy FROM metadata_columns WHERE connection_id=? AND database_name=? AND status='CONFIRMED' AND assigned_policy IS NOT NULL AND assigned_policy<>'NONE' ORDER BY table_name,column_name", mapping.get("connection_id"), physicalDatabase);
    }

    public List<String> tableColumns(final long connectionId, final String database, final String table) {
        List<String> physicalOrder = jdbc.queryForList("SELECT column_name FROM managed_columns WHERE connection_id=? AND database_name=? AND table_name=? ORDER BY ordinal_position",
                String.class, connectionId, database, table);
        if (!physicalOrder.isEmpty()) return physicalOrder;
        return jdbc.queryForList("SELECT column_name FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? ORDER BY id",
                String.class, connectionId, database, table);
    }

    public String policyForColumn(final long connectionId, final String database, final String table, final String column) {
        List<String> rows = jdbc.queryForList("SELECT assigned_policy FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=? AND status='CONFIRMED' AND assigned_policy IS NOT NULL AND assigned_policy<>'NONE'",
                String.class, connectionId, database, table, column);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    public boolean inheritPolicy(final long connectionId, final String database, final String table, final String column, final String policy) {
        int changed = jdbc.update("UPDATE metadata_columns SET assigned_policy=?,status='CONFIRMED',updated_at=? WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=?",
                policy, Instant.now().toString(), connectionId, database, table, column);
        if (changed > 0) audit("LINEAGE_POLICY_INHERITED", "database=" + database + ",table=" + table + ",column=" + column + ",policy=" + policy);
        return changed > 0;
    }

    public boolean inheritPolicyIfUnedited(long connectionId,String database,String table,String column,String policy,String activePolicy) {
        // Do not overwrite an administrator's draft while synchronizing a successful SQL statement.
        jdbc.update("UPDATE metadata_columns SET assigned_policy=?,status='CONFIRMED',updated_at=? WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=? AND COALESCE(NULLIF(assigned_policy,'NONE'),'')=?",
                policy,Instant.now().toString(),connectionId,database,table,column,activePolicy);
        return jdbc.queryForObject("SELECT COUNT(*) FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=? AND column_name=?",Integer.class,connectionId,database,table,column)>0;
    }

    public int renameTable(final long connectionId, final String database, final String oldTable, final String newTable) {
        int changed = jdbc.update("UPDATE metadata_columns SET table_name=?,updated_at=? WHERE connection_id=? AND database_name=? AND table_name=?",
                newTable, Instant.now().toString(), connectionId, database, oldTable);
        audit("LINEAGE_TABLE_RENAMED", "database=" + database + ",oldTable=" + oldTable + ",newTable=" + newTable + ",columns=" + changed);
        return changed;
    }

    public int dropTable(final long connectionId, final String database, final String table) {
        int changed = jdbc.update("DELETE FROM metadata_columns WHERE connection_id=? AND database_name=? AND table_name=?", connectionId, database, table);
        audit("LINEAGE_TABLE_DROPPED", "database=" + database + ",table=" + table + ",columns=" + changed);
        return changed;
    }

    @SuppressWarnings("unchecked")
    @org.springframework.transaction.annotation.Transactional
    public boolean saveLineageEvent(final Map<String, Object> event) {
        String eventId = required(event, "eventId");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM lineage_events WHERE event_id=?", Integer.class, eventId);
        if (exists != null && exists > 0) return false;
        jdbc.update("INSERT INTO lineage_events(event_id,event_type,logical_database,statement_type,sql_digest,payload,created_at) VALUES(?,?,?,?,?,?,?)",
                eventId, text(event, "eventType", "QUERY"), text(event, "logicalDatabase", ""), text(event, "statementType", ""),
                text(event, "sqlDigest", ""), json(event), text(event, "eventTime", Instant.now().toString()));
        Object rawEdges = event.get("edges");
        if (rawEdges instanceof List) {
            for (Object rawEdge : (List<?>) rawEdges) {
                if (!(rawEdge instanceof Map)) continue;
                Map<String, Object> edge = (Map<String, Object>) rawEdge;
                jdbc.update("INSERT INTO lineage_edges(event_id,source_database,source_table,source_column,target_database,target_table,target_column,transform_type,inherited_policy,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        eventId, text(edge, "sourceDatabase", ""), text(edge, "sourceTable", ""), text(edge, "sourceColumn", ""),
                        text(edge, "targetDatabase", ""), text(edge, "targetTable", ""), text(edge, "targetColumn", ""),
                        text(edge, "transformType", ""), text(edge, "inheritedPolicy", ""), Instant.now().toString());
            }
        }
        return true;
    }

    public List<Map<String, Object>> lineageEvents() {
        return jdbc.queryForList("SELECT id,event_id AS eventId,event_type AS eventType,logical_database AS logicalDatabase,statement_type AS statementType,sql_digest AS sqlDigest,created_at AS createdAt FROM lineage_events ORDER BY id DESC LIMIT 500");
    }

    public List<Map<String, Object>> lineageEdges(final String eventId) {
        return jdbc.queryForList("SELECT id,event_id AS eventId,source_database AS sourceDatabase,source_table AS sourceTable,source_column AS sourceColumn,target_database AS targetDatabase,target_table AS targetTable,target_column AS targetColumn,transform_type AS transformType,inherited_policy AS inheritedPolicy,created_at AS createdAt FROM lineage_edges WHERE event_id=? ORDER BY id", eventId);
    }

    public void audit(final String action, final String detail) {
        jdbc.update("INSERT INTO audit_log(action,detail,created_at) VALUES(?,?,?)", action, detail, Instant.now().toString());
    }

    private String json(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String required(final Map<String, Object> request, final String key) {
        String value = text(request, key, "");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String text(final Map<String, Object> request, final String key, final String fallback) {
        Object value = request.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(final Map<String, Object> request, final String key, final int fallback) {
        Object value = request.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }
}
