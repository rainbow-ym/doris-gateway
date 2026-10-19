package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class AccountAccessService {
    private final AdminStore store;
    private final AccountAccessStore access;
    private final PolicyCatalog catalog;
    private final DistSqlService proxy;
    private final byte[] token;
    @org.springframework.beans.factory.annotation.Autowired
    PublishedPolicyStore published;
    public AccountAccessService(AdminStore store,AccountAccessStore access,PolicyCatalog catalog,DistSqlService proxy,
                                @Value("${gateway-admin.lineage-token}") String token) {
        this.store=store;this.access=access;this.catalog=catalog;this.proxy=proxy;
        this.token=token==null?new byte[0]:token.getBytes(StandardCharsets.UTF_8);
    }
    public void authenticate(String supplied) {
        if(token.length<24 || supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("网关内部接口认证失败。");
    }
    public Map<String,Object> resolve(String username,String database) throws Exception {
        long epoch=access.lifecycleEpoch(username);
        access.migrate(store);
        if(username.equals(proxy.controlUsername())) return Collections.singletonMap("mode","CONTROL");
        List<Map<String,Object>> rows=access.accounts(database,username);
        if(rows.isEmpty()) throw new SecurityException("当前用户尚未关联该数据库："+database);
        Map<String,Object> first=rows.get(0);
        long id=((Number)first.get("id")).longValue();
        for(Map<String,Object> row:rows) {
            long other=((Number)row.get("id")).longValue();
            if(!sameEndpoint(first,row) || !store.connectionPassword(id).equals(store.connectionPassword(other)))
                throw new SecurityException("当前用户存在冲突的数据库连接，请检查账号关联。");
        }
        Map<String,Object> mapping=store.anyMapping(database);
        if(mapping.isEmpty()) {
            for(Map<String,Object> candidate:store.logicalDatabases())
                if(database.equalsIgnoreCase(String.valueOf(candidate.get("logicalName")))) mapping=store.anyMapping(String.valueOf(candidate.get("logicalName")));
        }
        if(mapping.isEmpty()) throw new SecurityException("逻辑库不存在。");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("mode","USER");result.put("connectionId",id);result.put("username",first.get("username"));
        result.put("epoch",epoch);
        result.put("permissionEpoch",access.permissionEpoch(username));
        result.put("password",store.connectionPassword(id));
        result.put("url",url(first,String.valueOf(mapping.get("physical_database"))));
        return result;
    }
    static boolean sameEndpoint(Map<String,Object> a,Map<String,Object> b) {
        return String.valueOf(a.get("host")).equalsIgnoreCase(String.valueOf(b.get("host"))) && String.valueOf(a.get("port")).equals(String.valueOf(b.get("port")));
    }
    static String url(Map<String,Object> source,String db) throws Exception {
        return "jdbc:mysql://"+source.get("host")+":"+source.get("port")+"/"+java.net.URLEncoder.encode(db,"UTF-8").replace("+","%20")
                +"?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=30000";
    }
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> policyPlan(String username,String db) {
        access.migrate(store);
        if(!username.equals(proxy.controlUsername()) && access.accounts(db,username).isEmpty()) throw new SecurityException("当前用户未关联该数据库。");
        for(Map<String,Object> mapping:store.logicalDatabases()) if(db.equalsIgnoreCase(String.valueOf(mapping.get("logicalName")))) {db=String.valueOf(mapping.get("logicalName"));break;}
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rules",access.policies(username,db));
        Map<String,Object> active=published.active(db);
        result.put("version",active.get("version"));
        result.put("columns",active.get("columns"));
        result.put("globalRules",active.get("globalRules"));
        result.put("algorithms",catalog.all());
        return result;
    }
    public void savePolicy(Map<String,Object> r) {
        String user=required(r,"username"), db=required(r,"logicalName"), action=required(r,"action");
        if(access.accounts(db,user).isEmpty()) throw new IllegalArgumentException("请先关联用户与数据库。");
        if(!Arrays.asList("CLEAR","MASK","DENY").contains(action)) throw new IllegalArgumentException("返回动作必须为 CLEAR、MASK 或 DENY；加密请选择 MASK 和返回加密算法。");
        String policy=String.valueOf(r.getOrDefault("policyId",""));
        if("MASK".equals(action) && (policy.isEmpty() || catalog.get(policy)==null)) throw new IllegalArgumentException("请选择有效脱敏或加密算法。");
        String table=String.valueOf(r.getOrDefault("tableName","*")), column=String.valueOf(r.getOrDefault("columnName","*"));
        if("*".equals(table)!= "*".equals(column)) throw new IllegalArgumentException("精确字段策略必须同时选择表和字段；类型策略使用两个星号。");
        String label=String.valueOf(r.getOrDefault("sensitiveType","*"));
        if(!"*".equals(label) && catalog.get(label)==null) throw new IllegalArgumentException("敏感类型无效。");
        access.savePolicy(user,db,table,column,label,action,policy);
        store.audit("USER_OUTPUT_POLICY_UPDATED","user="+user+",database="+db+",table="+table+",column="+column+",action="+action);
    }
    public synchronized void refresh(long id) throws Exception {
        Map<String,Object> source=store.connection(id);
        try(Connection c=DriverManager.getConnection(url(source,"information_schema"),String.valueOf(source.get("username")),store.connectionPassword(id))) {
            String identity;
            try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT CURRENT_USER()")) { rs.next();identity=rs.getString(1); }
            List<Map<String,Object>> grants=new ArrayList<>();
            try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SHOW GRANTS")) {
                while(rs.next()) { Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));grants.add(row); }
            }
            access.snapshot(id,identity,new ObjectMapper().writeValueAsString(grants),"ACTIVE","授权快照已读取；实际操作由 Doris 最终校验。");
            access.replaceScopes(id,DorisPrivilegeSnapshot.parse(grants));
            // SHOW TABLES gives a visibility projection only, never write or all-column authorization.
            for(Map<String,Object> binding:access.bindings()) {
                if(((Number)binding.get("connectionId")).longValue()!=id || !"ACTIVE".equals(binding.get("status"))) continue;
                String db=String.valueOf(binding.get("logicalName"));Map<String,Object> mapping=store.anyMapping(db);
                project(db);
                Set<String> visible=new HashSet<>();
                try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SHOW TABLES FROM "+OnboardingService.identifier(String.valueOf(mapping.get("physical_database"))))) {
                    while(rs.next()) visible.add(rs.getString(1));
                }
                for(Map<String,Object> object:store.managedObjects()) {
                    if(!String.valueOf(mapping.get("physical_database")).equals(String.valueOf(object.get("databaseName")))) continue;
                    long objectId=((Number)object.get("id")).longValue();
                    access.access(id,objectId,"VISIBILITY",visible.contains(String.valueOf(object.get("tableName")))?"ALLOWED":"UNKNOWN","Doris SHOW TABLES；不代表完整字段或写入授权");
                }
            }
        } catch(Exception e) { access.snapshot(id,"","[]","UNKNOWN",Errors.explain(e));throw e; }
    }
    public void project(String database) {
        Map<String,Object> mapping=store.anyMapping(database);if(mapping.isEmpty())return;
        access.invalidate(String.valueOf(mapping.get("physical_database")));
        for(Map<String,Object> binding:access.bindings()) {
            if(!database.equals(binding.get("logicalName"))||!"ACTIVE".equals(binding.get("status")))continue;
            long id=((Number)binding.get("connectionId")).longValue();
            List<DorisPrivilegeSnapshot.Scope> scopes=access.scopes(id);
            for(Map<String,Object> object:store.managedObjects()) {
                if(!String.valueOf(mapping.get("connection_id")).equals(String.valueOf(object.get("connectionId")))
                        || !String.valueOf(mapping.get("physical_database")).equals(object.get("databaseName")))continue;
                for(String privilege:DorisPrivilegeSnapshot.OPERATIONS) {
                    String state="UNKNOWN",evidence="未匹配完整授权；实际语句交由 Doris 校验";
                    if(!"ACTIVE".equals(object.get("status"))) {state="DENIED";evidence="对象未处于可访问状态";}
                    else for(DorisPrivilegeSnapshot.Scope scope:scopes) if(scope.matches(String.valueOf(object.get("databaseName")),String.valueOf(object.get("tableName")),privilege)) {
                        state="ALLOWED";evidence=scope.source+":"+scope.catalog+"."+scope.database+"."+scope.table;break;
                    }
                    access.access(id,((Number)object.get("id")).longValue(),privilege,state,evidence);
                }
            }
        }
    }
    public void objectChanged(String database,String type,String target,String renamed) {
        if("DROP".equals(type))access.dropObjectPolicies(database,target);
        if("RENAME".equals(type))access.renameObjectPolicies(database,target,renamed);
        project(database);
    }
    @Scheduled(fixedDelayString="${gateway-admin.permission-refresh-ms:60000}")
    public void refreshAll() {
        access.migrate(store);
        Set<Long> ids=new LinkedHashSet<>();
        for(Map<String,Object> row:access.bindings()) if("ACTIVE".equals(row.get("status"))) ids.add(((Number)row.get("connectionId")).longValue());
        for(Long id:ids) try { refresh(id); } catch(Exception ignored) { /* Snapshot records UNKNOWN; never grants access. */ }
    }
    private static String required(Map<String,Object> r,String key) { String v=String.valueOf(r.getOrDefault(key,""));if(v.trim().isEmpty())throw new IllegalArgumentException(key+"不能为空。");return v; }
}
