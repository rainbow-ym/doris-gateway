package com.example.doris.gateway.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import java.util.*;

/** Worker-local durable publication journal. Draft metadata is never a runtime policy. */
@Repository
public class PublishedPolicyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public PublishedPolicyStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS policy_publications (version_id BIGINT PRIMARY KEY, database_name VARCHAR(255) NOT NULL, before_json CLOB, snapshot_json CLOB NOT NULL, status VARCHAR(32) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS active_policy_snapshots (database_name VARCHAR(255) PRIMARY KEY, version_id BIGINT NOT NULL, snapshot_json CLOB NOT NULL)");
    }
    public Map<String,Object> active(String database) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT version_id,snapshot_json FROM active_policy_snapshots WHERE database_name=?",database);
        if(rows.isEmpty()) throw new IllegalStateException("该逻辑库尚无经过校验的生效策略快照，请先发布策略："+database);
        Map<String,Object> result=decode(String.valueOf(rows.get(0).get("snapshot_json")));
        result.put("version",rows.get(0).get("version_id"));
        return result;
    }
    public boolean hasActive(String database) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM active_policy_snapshots WHERE database_name=?",Integer.class,database)>0;
    }
    public void requireNoPending(String database) {
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM policy_publications WHERE database_name=? AND status IN ('APPLYING','RECOVERY_REQUIRED','RECOVERING')",Integer.class,database);
        if(pending!=0) throw new IllegalStateException("存在未确认完成的策略发布，请先核实并恢复运行规则，禁止覆盖发布记录："+database);
    }
    @Transactional public void begin(long version,String database,Map<String,Object> snapshot) {
        requireNoPending(database);
        List<String> previous=jdbc.queryForList("SELECT snapshot_json FROM active_policy_snapshots WHERE database_name=?",String.class,database);
        jdbc.update("INSERT INTO policy_publications(version_id,database_name,before_json,snapshot_json,status) VALUES(?,?,?,?,'APPLYING')",version,database,previous.isEmpty()?null:previous.get(0),encode(snapshot));
    }
    @Transactional public void activate(long version,String status) {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM policy_publications WHERE version_id=? FOR UPDATE",version);
        if(!"APPLYING".equals(row.get("status"))) throw new IllegalStateException("策略发布状态已变化，拒绝激活。");
        jdbc.update("MERGE INTO active_policy_snapshots(database_name,version_id,snapshot_json) KEY(database_name) VALUES(?,?,?)",row.get("database_name"),version,row.get("snapshot_json"));
        jdbc.update("UPDATE policy_publications SET status=? WHERE version_id=?",status,version);
        jdbc.update("UPDATE publish_versions SET status=?,message=? WHERE id=?",status,"运行规则已校验，生效快照已切换；草稿配置保持不变。",version);
    }
    @Transactional public void failed(long version,boolean recovered,String message) {
        String status=recovered?"FAILED":"RECOVERY_REQUIRED";
        jdbc.update("UPDATE policy_publications SET status=? WHERE version_id=?",status,version);
        jdbc.update("UPDATE publish_versions SET status=?,message=? WHERE id=?",status,message,version);
    }
    @Transactional public void activateRename(long version,String status,String executionId) {
        activate(version,status);
        if(jdbc.update("UPDATE managed_sql_executions SET rename_policy_version=? WHERE execution_id=? AND rename_registry_applied=TRUE AND success_received=TRUE AND status='SYNCING'",version,executionId)!=1)
            throw new IllegalStateException("改名任务状态已变化，禁止激活策略。");
    }
    public Map<String,Object> before(long version) {
        List<String> rows=jdbc.queryForList("SELECT before_json FROM policy_publications WHERE version_id=? AND status IN ('PUBLISHED','ROLLED_BACK')",String.class,version);
        if(rows.isEmpty()||rows.get(0)==null) throw new IllegalArgumentException("该版本没有可验证的前一策略快照，不能回滚；请重新确认并发布策略。");
        return decode(rows.get(0));
    }
    public Map<String,Object> publication(long version) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM policy_publications WHERE version_id=?",version);
        if(rows.isEmpty())throw new IllegalArgumentException("该版本没有可核实的发布日志，不能自动恢复。");
        return rows.get(0);
    }
    @Transactional public void beginRecovery(long version,String actor) {
        int changed=jdbc.update("UPDATE policy_publications SET status='RECOVERING' WHERE version_id=? AND status IN ('APPLYING','RECOVERY_REQUIRED','RECOVERING')",version);
        if(changed!=1)throw new IllegalStateException("该发布已结束或状态已变化，不能开始恢复。");
        jdbc.update("UPDATE publish_versions SET status='RECOVERING',message=? WHERE id=?","正在核实并恢复运行规则，不激活候选策略、不重放业务 SQL。",version);
        recoveryAudit("POLICY_RECOVERY_STARTED",version,actor);
    }
    @Transactional public void finishRecovery(long version,long expectedActiveVersion,String actor) {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM policy_publications WHERE version_id=? FOR UPDATE",version);
        if(!"RECOVERING".equals(row.get("status")))throw new IllegalStateException("恢复状态已变化，拒绝完成。");
        List<Long> active=jdbc.queryForList("SELECT version_id FROM active_policy_snapshots WHERE database_name=? FOR UPDATE",Long.class,row.get("database_name"));
        if((active.isEmpty()?0L:active.get(0))!=expectedActiveVersion)throw new IllegalStateException("生效版本已变化，恢复结果需重新核实。");
        jdbc.update("UPDATE policy_publications SET status='RECOVERED_ABORTED' WHERE version_id=?",version);
        jdbc.update("UPDATE publish_versions SET status='RECOVERED_ABORTED',message=? WHERE id=?","运行规则已恢复并读回校验；本次发布已终止，生效快照与草稿不变。业务对象状态未自动恢复。",version);
        recoveryAudit("POLICY_RECOVERY_COMPLETED",version,actor);
    }
    @Transactional public void recoveryFailed(long version,String actor) {
        jdbc.update("UPDATE policy_publications SET status='RECOVERY_REQUIRED' WHERE version_id=? AND status='RECOVERING'",version);
        jdbc.update("UPDATE publish_versions SET status='RECOVERY_REQUIRED',message=? WHERE id=? AND status='RECOVERING'","恢复未能完成校验，保持发布阻断；请检查 Proxy 连通性及运行日志后重新核实。",version);
        recoveryAudit("POLICY_RECOVERY_FAILED",version,actor);
    }
    private void recoveryAudit(String action,long version,String actor) {
        jdbc.update("INSERT INTO audit_log(action,detail,created_at) VALUES(?,?,?)",action,"version="+version+",actor="+actor,java.time.Instant.now().toString());
    }
    private String encode(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("策略快照序列化失败。",e);} }
    private Map<String,Object> decode(String value) {try{return mapper.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("策略快照损坏，已停止返回结果。",e);} }
}
