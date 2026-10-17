package com.example.doris.gateway.admin;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;

/** Account projections are disposable; Doris remains the authorization authority. */
@Repository
public class AccountAccessStore {
    private final JdbcTemplate jdbc;
    public AccountAccessStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_lifecycle_states(username VARCHAR(128) PRIMARY KEY,status VARCHAR(32),epoch BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_database_bindings (connection_id BIGINT NOT NULL, logical_name VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL, updated_at VARCHAR(64) NOT NULL, PRIMARY KEY(connection_id,logical_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_privilege_snapshots (connection_id BIGINT PRIMARY KEY, matched_identity VARCHAR(255), grants_json CLOB, status VARCHAR(32) NOT NULL, message CLOB, updated_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_privilege_scopes (id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT NOT NULL, catalog_name VARCHAR(255), database_name VARCHAR(255), table_name VARCHAR(255), privilege_name VARCHAR(64), grant_source VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_object_access (connection_id BIGINT NOT NULL, object_id BIGINT NOT NULL, privilege_name VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL, evidence VARCHAR(255), updated_at VARCHAR(64) NOT NULL, PRIMARY KEY(connection_id,object_id,privilege_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_output_policies (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128) NOT NULL, logical_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL DEFAULT '*', column_name VARCHAR(255) NOT NULL DEFAULT '*', sensitive_type VARCHAR(64) NOT NULL DEFAULT '*', action VARCHAR(32) NOT NULL, policy_id VARCHAR(64), updated_at VARCHAR(64) NOT NULL, UNIQUE(username,logical_name,table_name,column_name,sensitive_type))");
    }

    public void migrate(AdminStore store) {
        for(Map<String,Object> m:store.logicalDatabases()) {
            long id=((Number)m.get("connectionId")).longValue();
            String db=String.valueOf(m.get("logicalName"));
            if(jdbc.queryForObject("SELECT COUNT(*) FROM account_database_bindings WHERE connection_id=? AND logical_name=?",Integer.class,id,db)==0)
                bind(id,db,String.valueOf(m.get("status")));
        }
    }
    public void bind(long id,String db,String status) {
        jdbc.update("MERGE INTO account_database_bindings(connection_id,logical_name,status,updated_at) KEY(connection_id,logical_name) VALUES(?,?,?,?)",id,db,status,Instant.now().toString());
    }
    public void lifecycle(String user,String status,boolean increment) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM account_lifecycle_states WHERE username=?",Integer.class,user)==0)
            jdbc.update("INSERT INTO account_lifecycle_states VALUES(?,?,?)",user,status,increment?1:0);
        else jdbc.update("UPDATE account_lifecycle_states SET status=?,epoch=epoch+? WHERE username=?",status,increment?1:0,user);
    }
    public long lifecycleEpoch(String user) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM account_lifecycle_states WHERE username=?",user);
        if(rows.isEmpty())return 0;
        if(!"ACTIVE".equals(rows.get(0).get("status")))throw new SecurityException("账号已禁用或同步未完成，请查看账号同步任务。");
        return ((Number)rows.get(0).get("epoch")).longValue();
    }
    public boolean lifecycleActive(String user) {
        try {lifecycleEpoch(user);return true;}catch(SecurityException e){return false;}
    }
    public boolean lifecycleManaged(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_lifecycle_states WHERE username=?",Integer.class,user)>0;
    }
    public List<Map<String,Object>> bindings() {
        return jdbc.queryForList("SELECT b.connection_id AS connectionId,b.logical_name AS logicalName,b.status,c.username,c.name AS connectionName FROM account_database_bindings b JOIN connections c ON c.id=b.connection_id ORDER BY b.logical_name,c.username");
    }
    public List<Map<String,Object>> accounts(String db,String username) {
        return jdbc.queryForList("SELECT c.* FROM account_database_bindings b JOIN connections c ON c.id=b.connection_id JOIN logical_databases d ON d.logical_name=b.logical_name WHERE LOWER(b.logical_name)=LOWER(?) AND c.username=? AND b.status='ACTIVE' AND d.status='ACTIVE'",db,username);
    }
    public int dependencies(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM account_database_bindings WHERE connection_id=?",Integer.class,id); }
    public void removeDatabase(String db) { jdbc.update("DELETE FROM account_database_bindings WHERE logical_name=?",db); }
    public void unbind(long id,String db) { jdbc.update("DELETE FROM account_database_bindings WHERE connection_id=? AND logical_name=?",id,db); }
    public void snapshot(long id,String identity,String grants,String status,String message) {
        jdbc.update("MERGE INTO account_privilege_snapshots(connection_id,matched_identity,grants_json,status,message,updated_at) KEY(connection_id) VALUES(?,?,?,?,?,?)",id,identity,grants,status,message,Instant.now().toString());
    }
    public List<Map<String,Object>> snapshots() { return jdbc.queryForList("SELECT connection_id AS connectionId,matched_identity AS matchedIdentity,status,message,updated_at AS updatedAt FROM account_privilege_snapshots"); }
    public void access(long id,long object,String privilege,String state,String evidence) {
        jdbc.update("MERGE INTO account_object_access(connection_id,object_id,privilege_name,state,evidence,updated_at) KEY(connection_id,object_id,privilege_name) VALUES(?,?,?,?,?,?)",id,object,privilege,state,evidence,Instant.now().toString());
    }
    public void invalidate(String db) {
        jdbc.update("UPDATE account_object_access SET state='STALE' WHERE object_id IN (SELECT id FROM managed_objects WHERE database_name=?)",db);
    }
    @org.springframework.transaction.annotation.Transactional
    public void replaceScopes(long id,List<DorisPrivilegeSnapshot.Scope> scopes) {
        jdbc.update("DELETE FROM account_privilege_scopes WHERE connection_id=?",id);
        for(DorisPrivilegeSnapshot.Scope s:scopes)jdbc.update("INSERT INTO account_privilege_scopes(connection_id,catalog_name,database_name,table_name,privilege_name,grant_source) VALUES(?,?,?,?,?,?)",id,s.catalog,s.database,s.table,s.privilege,s.source);
    }
    public List<DorisPrivilegeSnapshot.Scope> scopes(long id) {
        return jdbc.query("SELECT * FROM account_privilege_scopes WHERE connection_id=?",(rs,n)->new DorisPrivilegeSnapshot.Scope(rs.getString("catalog_name"),rs.getString("database_name"),rs.getString("table_name"),rs.getString("privilege_name"),rs.getString("grant_source")),id);
    }
    public List<Map<String,Object>> accessRows(long id) {
        return jdbc.queryForList("SELECT a.object_id AS objectId,o.database_name AS databaseName,o.table_name AS tableName,a.privilege_name AS privilege,a.state,a.evidence,a.updated_at AS updatedAt FROM account_object_access a JOIN managed_objects o ON o.id=a.object_id WHERE a.connection_id=?",id);
    }
    public List<Map<String,Object>> policies(String username,String db) {
        return jdbc.queryForList("SELECT id,username,logical_name AS logicalName,table_name AS tableName,column_name AS columnName,sensitive_type AS sensitiveType,action,policy_id AS policyId FROM user_output_policies WHERE username=? AND LOWER(logical_name)=LOWER(?) ORDER BY id",username,db);
    }
    public void savePolicy(String user,String db,String table,String column,String label,String action,String policy) {
        jdbc.update("MERGE INTO user_output_policies(username,logical_name,table_name,column_name,sensitive_type,action,policy_id,updated_at) KEY(username,logical_name,table_name,column_name,sensitive_type) VALUES(?,?,?,?,?,?,?,?)",user,db,table,column,label,action,policy,Instant.now().toString());
    }
    public void deletePolicy(long id) { jdbc.update("DELETE FROM user_output_policies WHERE id=?",id); }
    public void dropObjectPolicies(String db,String table) {jdbc.update("DELETE FROM user_output_policies WHERE logical_name=? AND table_name=?",db,table);}
    public void renameObjectPolicies(String db,String oldName,String newName) {
        jdbc.update("DELETE FROM user_output_policies WHERE logical_name=? AND table_name=?",db,newName);
        jdbc.update("UPDATE user_output_policies SET table_name=? WHERE logical_name=? AND table_name=?",newName,db,oldName);
    }
}
