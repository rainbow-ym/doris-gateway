package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shardingsphere.mask.spi.MaskAlgorithm;
import org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader;
import java.util.*;

/** Immutable per-query policy snapshot. ThreadLocal only bridges synchronous merge construction. */
public final class UserOutputPlan {
    private static final ThreadLocal<String> SUBJECT=new ThreadLocal<>();
    private final Map<String,Map<String,Object>> snapshots=new HashMap<>();
    private final Map<String,MaskAlgorithm> algorithms=new HashMap<>();
    private final String username;
    public static void enter(String user) { SUBJECT.set(user); }
    public static void exit() { SUBJECT.remove(); }
    public static UserOutputPlan capture(String database) {
        if(!UserBackendConnections.enabled()) return null;
        if(SUBJECT.get()==null) throw new IllegalStateException("缺少查询用户身份，拒绝生成结果。");
        UserOutputPlan result=new UserOutputPlan(SUBJECT.get());result.snapshot(database);return result;
    }
    private UserOutputPlan(String username) {this.username=username;}
    @SuppressWarnings("unchecked")
    private Map<String,Object> snapshot(String db) {
        String key=db.toLowerCase(Locale.ROOT);
        if(!snapshots.containsKey(key)) {
            try { snapshots.put(key,new ObjectMapper().readValue(LineageEventEmitter.accountRequest("user-policies",username,db),Map.class)); }
            catch(Exception e) {throw new IllegalStateException("无法读取当前用户返回策略，已停止返回结果。",e);}
        }
        return snapshots.get(key);
    }
    @SuppressWarnings("unchecked")
    public Decision decision(String db,String table,String column) {
        Map<String,Object> snapshot=snapshot(db);
        String label="";
        for(Map<String,Object> c:(List<Map<String,Object>>)snapshot.get("columns"))
            if(eq(c.get("tableName"),table)&&eq(c.get("columnName"),column)) label=String.valueOf(c.get("assignedPolicy"));
        Map<String,Object> selected=null;int rank=-1;
        for(Map<String,Object> r:(List<Map<String,Object>>)snapshot.get("rules")) {
            boolean exact=eq(r.get("tableName"),table)&&eq(r.get("columnName"),column);
            boolean wildcard="*".equals(r.get("tableName"))&&"*".equals(r.get("columnName"));
            boolean type="*".equals(r.get("sensitiveType"))||eq(r.get("sensitiveType"),label);
            if((exact||wildcard)&&type) {
                // Type-wide policies apply to sensitive columns only.
                if(!exact&&label.isEmpty()) continue;
                int current=(exact?4:0)+("*".equals(r.get("sensitiveType"))?0:1);
                if(current>rank) {selected=r;rank=current;}
                else if(current==rank) throw new IllegalStateException("用户返回策略存在同优先级冲突。");
            }
        }
        if(selected==null) return publishedDecision(snapshot,table,column,!label.isEmpty());
        String action=String.valueOf(selected.get("action"));
        if(!"MASK".equals(action)) return new Decision(action,null,false);
        String id=String.valueOf(selected.get("policyId"));
        String algorithmKey=id+'\u0000'+db+'\u0000'+table+'\u0000'+column;
        if(!algorithms.containsKey(algorithmKey)) {
            for(Map<String,Object> p:(List<Map<String,Object>>)snapshot.get("algorithms")) if(id.equals(p.get("id"))) {
                Properties props=algorithmProperties(id,db,table,column,(Map<?,?>)p.get("props"));
                algorithms.put(algorithmKey,TypedSPILoader.getService(MaskAlgorithm.class,String.valueOf(p.get("algorithmType")),props));
            }
            if(!algorithms.containsKey(algorithmKey)) throw new IllegalStateException("返回策略算法不存在："+id);
        }
        return new Decision(action,algorithms.get(algorithmKey),true);
    }
    @SuppressWarnings("unchecked")
    static Decision publishedDecision(Map<String,Object> snapshot,String table,String column,boolean required) {
        if(!(snapshot.get("version") instanceof Number) || !(snapshot.get("globalRules") instanceof List))
            throw new IllegalStateException("控制面未提供有效的生效策略快照，请完成版本升级及策略发布。");
        Map<String,Object> selected=null;
        for(Map<String,Object> rule:(List<Map<String,Object>>)snapshot.get("globalRules")) {
            if(eq(rule.get("table"),table)&&eq(rule.get("column"),column)) {
                if(selected!=null)throw new IllegalStateException("生效策略快照含有重复字段规则。");
                selected=rule;
            }
        }
        if(selected==null) {
            if(required)throw new IllegalStateException("生效策略快照缺少敏感字段算法，已停止返回结果。");
            return new Decision("CLEAR",null,false);
        }
        Properties props=new Properties();props.putAll((Map<?,?>)selected.get("props"));
        return new Decision("MASK",TypedSPILoader.getService(MaskAlgorithm.class,String.valueOf(selected.get("algorithmType")),props),true);
    }
    static Properties algorithmProperties(String id,String db,String table,String column,Map<?,?> configured) {
        Properties result=new Properties();result.putAll(configured);
        if("OUTPUT_AES_GCM".equals(id)) {
            result.setProperty("key-env","GATEWAY_OUTPUT_AES_KEY_B64");
            result.setProperty("key-id","output-key-v1");
            result.setProperty("aad",db+"."+table+"."+column);
        }
        return result;
    }
    public static Optional<MaskAlgorithm> requirePublishedProtection(Optional<MaskAlgorithm> algorithm,boolean required) {
        if(required&&!algorithm.isPresent())throw new IllegalStateException("敏感字段的生效保护规则暂不可用，已停止返回结果；请检查策略发布状态。");
        return algorithm;
    }
    private static boolean eq(Object a,String b) {return a!=null&&String.valueOf(a).equalsIgnoreCase(b);}
    public static final class Decision {
        public final String action;public final MaskAlgorithm algorithm;public final boolean protectionRequired;
        Decision(String action,MaskAlgorithm algorithm,boolean protectionRequired){this.action=action;this.algorithm=algorithm;this.protectionRequired=protectionRequired;}
    }
}
