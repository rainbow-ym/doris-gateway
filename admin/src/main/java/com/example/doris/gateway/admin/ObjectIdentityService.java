package com.example.doris.gateway.admin;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import java.util.*;

/** Opt-in legacy migration, then fail-closed identity checks. Never silently rebaseline a replacement. */
@Service
public class ObjectIdentityService {
    private final JdbcTemplate jdbc;private final AdminStore store;private final ObjectIdentityProvider provider;
    public ObjectIdentityService(JdbcTemplate jdbc,AdminStore store,ObjectIdentityProvider provider){this.jdbc=jdbc;this.store=store;this.provider=provider;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS physical_object_bindings(object_id BIGINT NOT NULL,generation_id VARCHAR(64) NOT NULL,physical_identity VARCHAR(128) NOT NULL,invalidated BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(object_id,generation_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS object_identity_enforcement(connection_id BIGINT NOT NULL,database_name VARCHAR(255) NOT NULL,PRIMARY KEY(connection_id,database_name))");
    }
    public boolean enabled(long connection,String database){return jdbc.queryForObject("SELECT COUNT(*) FROM object_identity_enforcement WHERE connection_id=? AND database_name=?",Integer.class,connection,database)>0;}
    /** New external sources only. Replacement objects need a separate, explicit lineage retirement workflow. */
    public Map<String,Object> previewNewSource(long objectId)throws Exception {
        Map<String,Object> object=store.managedObject(objectId);
        requireNewSource(object);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",object.get("id"));result.put("generation_id",object.get("generation_id"));
        result.put("database",object.get("database_name"));result.put("table",object.get("table_name"));
        result.put("physicalIdentity",provider.identity(((Number)object.get("connection_id")).longValue(),String.valueOf(object.get("database_name")),String.valueOf(object.get("table_name"))));
        return result;
    }
    private void requireNewSource(Map<String,Object> object) {
        long connection=((Number)object.get("connection_id")).longValue();
        String db=String.valueOf(object.get("database_name")),table=String.valueOf(object.get("table_name"));
        Map<String,Object> mapping=store.logicalDatabaseFor(connection,db);
        if(!"ACTIVE".equals(mapping.get("status"))||!enabled(connection,db))throw new IllegalStateException("请先完成数据库接入与物理身份基线确认。");
        if(object.get("pending_execution_id")!=null)throw new IllegalStateException("对象仍由未完成任务占用，不能作为新来源接纳。");
        if(!"UNMANAGED".equals(object.get("status")))throw new IllegalStateException("只有尚未纳管的新表可以作为新来源；替换表须先处理旧血缘。");
        if(((Number)object.get("policy_version")).longValue()!=0||!bindings(object).isEmpty()
            ||jdbc.queryForObject("SELECT COUNT(*) FROM managed_sql_executions WHERE connection_id=? AND physical_database=? AND (LOWER(target_table)=LOWER(?) OR LOWER(rename_target_table)=LOWER(?))",Integer.class,connection,db,table,table)>0
            ||jdbc.queryForObject("SELECT COUNT(*) FROM lineage_edges WHERE (LOWER(source_database)=LOWER(?) AND LOWER(source_table)=LOWER(?)) OR (LOWER(target_database)=LOWER(?) AND LOWER(target_table)=LOWER(?))",Integer.class,db,table,db,table)>0)
            throw new IllegalStateException("该名称存在旧身份、策略或血缘记录，禁止按全新来源接纳。");
    }
    @Transactional(rollbackFor=Exception.class)
    public void confirmNewSource(long objectId,Map<String,Object> expected)throws Exception {
        List<Map<String,Object>> locked=jdbc.queryForList("SELECT * FROM managed_objects WHERE id=? FOR UPDATE",objectId);
        if(locked.size()!=1)throw new IllegalArgumentException("对象不存在，请重新扫描。");
        Map<String,Object> object=locked.get(0);
        requireNewSource(object);
        if(expected==null||!Objects.equals(object.get("generation_id"),expected.get("generation_id")))throw new IllegalStateException("对象代次已变化，请重新预览。");
        String physical=provider.identity(((Number)object.get("connection_id")).longValue(),String.valueOf(object.get("database_name")),String.valueOf(object.get("table_name")));
        if(!physical.equals(expected.get("physicalIdentity")))throw new IllegalStateException("物理对象已变化，请重新预览。");
        bind(object,physical);
        store.audit("EXTERNAL_SOURCE_IDENTITY_CONFIRMED","objectId="+objectId+",generation="+object.get("generation_id"));
        // Remain UNMANAGED: binding alone never grants query access. Existing approval publishes protection first.
    }
    public List<Map<String,Object>> preview(String logical)throws Exception {
        Map<String,Object> m=store.anyMapping(logical);if(m.isEmpty()||!"ACTIVE".equals(m.get("status")))throw new IllegalArgumentException("逻辑库尚未就绪。");
        long connection=((Number)m.get("connection_id")).longValue();String database=String.valueOf(m.get("physical_database"));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,generation_id,table_name FROM managed_objects WHERE connection_id=? AND database_name=? AND status='ACTIVE' ORDER BY id",connection,database);
        for(Map<String,Object> row:rows)row.put("physicalIdentity",provider.identity(connection,database,String.valueOf(row.get("table_name"))));
        return rows;
    }
    @Transactional(rollbackFor=Exception.class) public void confirm(String logical,List<Map<String,Object>> expected)throws Exception {
        Map<String,Object> m=store.anyMapping(logical);if(m.isEmpty()||!"ACTIVE".equals(m.get("status")))throw new IllegalArgumentException("逻辑库尚未就绪。");
        long connection=((Number)m.get("connection_id")).longValue();String db=String.valueOf(m.get("physical_database"));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM managed_objects WHERE connection_id=? AND database_name=? AND status='ACTIVE' ORDER BY id FOR UPDATE",connection,db);
        if(expected==null||rows.size()!=expected.size())throw new IllegalStateException("对象清单已变化，请重新预览。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM managed_objects WHERE connection_id=? AND database_name=? AND pending_execution_id IS NOT NULL",Integer.class,connection,db)>0)throw new IllegalStateException("存在未完成对象任务，禁止建立身份基线。");
        Set<Long> ids=new HashSet<>();
        for(Map<String,Object> object:rows){
            Map<String,Object> supplied=null;long id=((Number)object.get("id")).longValue();
            for(Map<String,Object> candidate:expected)if(candidate.get("id") instanceof Number&&((Number)candidate.get("id")).longValue()==id){if(supplied!=null)throw new IllegalArgumentException("对象重复。");supplied=candidate;}
            if(supplied==null||!ids.add(id)||!Objects.equals(object.get("generation_id"),supplied.get("generation_id")))throw new IllegalStateException("对象代次已变化。");
            String physical=provider.identity(connection,db,String.valueOf(object.get("table_name")));
            if(!physical.equals(supplied.get("physicalIdentity")))throw new IllegalStateException("物理对象已变化，请重新核实，不能沿用预览结果。");
            bind(object,physical);
        }
        jdbc.update("MERGE INTO object_identity_enforcement(connection_id,database_name) KEY(connection_id,database_name) VALUES(?,?)",connection,db);
        store.audit("OBJECT_IDENTITY_BASELINE_CONFIRMED","database="+logical+",objects="+rows.size());
    }
    private void bind(Map<String,Object> object,String physical){
        List<Map<String,Object>> prior=bindings(object);
        if(!prior.isEmpty()) {
            if(Boolean.TRUE.equals(prior.get(0).get("invalidated"))||!physical.equals(prior.get(0).get("physical_identity")))throw new IllegalStateException("已有物理身份不同或已隔离，禁止覆盖基线及旧血缘。");
            return;
        }
        jdbc.update("INSERT INTO physical_object_bindings(object_id,generation_id,physical_identity) VALUES(?,?,?)",object.get("id"),object.get("generation_id"),physical);
    }
    private List<Map<String,Object>> bindings(Map<String,Object> object){return jdbc.queryForList("SELECT * FROM physical_object_bindings WHERE object_id=? AND generation_id=?",object.get("id"),object.get("generation_id"));}
    public void require(long connection,String database,String table)throws Exception {
        if(!enabled(connection,database))return;
        requireObject(store.managedObject(connection,database,table),connection,database,table);
    }
    private void requireObject(Map<String,Object> object,long connection,String database,String physicalName)throws Exception {
        List<Map<String,Object>> rows=bindings(object);
        if(rows.size()!=1||Boolean.TRUE.equals(rows.get(0).get("invalidated")))throw new IllegalStateException("对象物理身份尚未确认或已隔离，禁止使用旧映射："+physicalName);
        String actual=provider.identity(connection,database,physicalName);
        if(!actual.equals(rows.get(0).get("physical_identity"))){
            jdbc.update("UPDATE physical_object_bindings SET invalidated=TRUE WHERE object_id=? AND generation_id=?",object.get("id"),object.get("generation_id"));
            throw new IllegalStateException("检测到同名对象被替换，旧血缘和旧任务已阻断："+physicalName);
        }
    }
    public void verifyExecution(Map<String,Object> execution)throws Exception {
        long connection=((Number)execution.get("connection_id")).longValue();String db=String.valueOf(execution.get("physical_database"));
        if(!enabled(connection,db))return;
        String type=String.valueOf(execution.get("statement_type")),table=String.valueOf(execution.get("target_table"));
        if("DROP".equals(type))return; // Existing DROP path separately proves physical absence.
        if("NOOP".equals(type)){require(connection,db,table);return;}
        if("RENAME".equals(type)) {
            String renamed=String.valueOf(execution.get("rename_target_table"));
            Map<String,Object> object=store.managedObject(connection,db,Boolean.TRUE.equals(execution.get("rename_registry_applied"))?renamed:table);
            requireObject(object,connection,db,renamed);return;
        }
        Map<String,Object> object=store.managedObject(connection,db,table);
        if(type.startsWith("CREATE")||"CTAS".equals(type)) {
            if(bindings(object).isEmpty()) {
                // Never infer the identity of an old interrupted create on a later recovery attempt.
                if(((Number)execution.get("recovery_attempts")).intValue()!=1)throw new IllegalStateException("新建对象首次确认缺少物理身份，不能通过同名存在推定恢复成功。");
                bind(object,provider.identity(connection,db,table));return;
            }
        }
        requireObject(object,connection,db,table);
    }
}
