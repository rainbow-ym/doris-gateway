package com.example.doris.gateway.admin;

import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OnboardingService {
    private final AdminStore store;
    private final DorisMetadataService metadata;
    private final DistSqlService proxy;
    private final GatewayAccounts accounts;
    private final AccountAccessStore access;
    @org.springframework.beans.factory.annotation.Value("${GATEWAY_MULTI_USER_ENABLED:false}")
    private boolean multiUserEnabled;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,String> inFlight=new ConcurrentHashMap<>();
    public OnboardingService(AdminStore store,DorisMetadataService metadata,DistSqlService proxy,GatewayAccounts accounts,AccountAccessStore access) {
        this.store=store;this.metadata=metadata;this.proxy=proxy;this.accounts=accounts;this.access=access;
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }

    public synchronized List<String> submit(long connectionId,List<String> databases) throws Exception {
        List<String> allowed=metadata.databases(connectionId);
        if(databases==null || databases.isEmpty()) throw new IllegalArgumentException("请至少选择一个数据库。");
        for(String database:databases) {
            if(!allowed.contains(database)) {
                String reason="当前连接未发现数据库："+database+"。请核对服务器地址、物理库是否存在、DWD_/DWH_ 范围及账号权限，然后重新发现。";
                Map<String,Object> old=store.anyMapping(database);
                if(!old.isEmpty() && ((Number)old.get("connection_id")).longValue()==connectionId && "FAILED".equals(old.get("status")))
                    store.saveLogicalDatabase(connectionId,database,String.valueOf(old.get("physical_database")),String.valueOf(old.get("storage_unit")),"FAILED",reason);
                throw new IllegalArgumentException(reason);
            }
            Map<String,Object> mapping=store.anyMapping(database);
            if(!mapping.isEmpty() && ((Number)mapping.get("connection_id")).longValue()!=connectionId) {
                if(!multiUserEnabled) throw new IllegalStateException("请先部署支持多用户的网关，并在网关与管理端同时启用 GATEWAY_MULTI_USER_ENABLED。");
                Map<String,Object> owner=store.connection(((Number)mapping.get("connection_id")).longValue());
                if(!AccountAccessService.sameEndpoint(owner,store.connection(connectionId)))
                    throw new IllegalStateException("同名逻辑库指向不同服务器，请核对数据源地址；不能自动合并不同集群。");
                if(!"ACTIVE".equals(mapping.get("status"))) throw new IllegalStateException("请先恢复该数据库的原接入任务，再关联其他账号。");
            }
        }
        List<String> ids=new ArrayList<>();
        for(String database:new LinkedHashSet<>(databases)) {
            if(inFlight.containsKey(database)) { ids.add(inFlight.get(database));continue; }
            Map<String,Object> m=store.anyMapping(database);
            if(!m.isEmpty() && "ACTIVE".equals(m.get("status"))) {
                access.bind(connectionId,database,"ACTIVE");
                try { accounts.synchronize(); } catch(Exception e) {access.bind(connectionId,database,"FAILED");throw e;}
                store.audit("ACCOUNT_DATABASE_ASSOCIATED","connectionId="+connectionId+",database="+database);
                continue;
            }
            String id=UUID.randomUUID().toString();store.newJob(id,connectionId,database);inFlight.put(database,id);ids.add(id);
            worker.submit(()->run(id,connectionId,database));
        }
        return ids;
    }

    private void run(String id,long connectionId,String database) {
        String stage="验证数据源";
        Map<String,Object> previous=store.anyMapping(database);
        String generated="ds_"+connectionId+"_"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","").substring(0,12);
        String unit=previous.isEmpty()||!String.valueOf(previous.get("storage_unit")).startsWith("ds_"+connectionId+"_")?generated:String.valueOf(previous.get("storage_unit"));
        try {
            progress(id,stage);metadata.test(connectionId);
            if(!metadata.databases(connectionId).contains(database)) throw new IllegalStateException("数据库已不在发现范围中，请重新发现。");
            store.saveLogicalDatabase(connectionId,database,database,unit,"SYNCING",stage);
            access.bind(connectionId,database,"SYNCING");
            stage="同步网关账号与授权";progress(id,stage);accounts.synchronize();
            stage="创建同名逻辑库";progress(id,stage);proxy.controlSql(null,"CREATE DATABASE IF NOT EXISTS "+identifier(database));
            stage="注册真实数据库";progress(id,stage);
            Map<String,Object> source=store.connection(connectionId);
            List<Map<String,Object>> units=proxy.storageUnits(database);
            boolean exists=false;
            for(Map<String,Object> row:units) {
                Object name=row.get("name");if(name==null) name=row.get("NAME");
                if(unit.equals(String.valueOf(name))) exists=true;
                else throw new IllegalStateException("该逻辑库存在未由本接入任务管理的存储单元，请先检查并解除旧映射，避免连接到错误数据库。");
            }
            String url="jdbc:mysql://"+source.get("host")+":"+source.get("port")+"/"+java.net.URLEncoder.encode(database,"UTF-8").replace("+","%20")+"?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";
            String definition=identifier(unit)+" (URL="+literal(url)+",USER="+literal(String.valueOf(source.get("username")))+",PASSWORD="+literal(store.connectionPassword(connectionId))+",PROPERTIES(\"minPoolSize\"=1,\"maxPoolSize\"=20))";
            proxy.controlSql(database,(exists?"ALTER":"REGISTER")+" STORAGE UNIT "+definition);
            stage="扫描字段与加载表";progress(id,stage);
            List<String> tables=metadata.tables(connectionId,database);
            for(String table:tables) {
                store.discoverManagedObject(connectionId,database,table,"TABLE");
                metadata.scanTable(connectionId,database,table);
                proxy.synchronizeSingleTable(database,unit,table);
                store.confirmRecommendationsForTable(connectionId,database,table);
            }
            stage="发布策略并校验";progress(id,stage);proxy.initializePolicies(database);
            for(String table:tables) {
                store.upsertManagedObject(connectionId,database,table,"TABLE","ACTIVE",null,"接入完成，策略已发布");
                store.copyMetadataColumnsToRegistry(connectionId,database,table,"ACTIVE");
            }
            store.saveLogicalDatabase(connectionId,database,database,unit,"ACTIVE","接入完成，"+tables.size()+" 张表；网关账号与数据源账号一致");
            access.bind(connectionId,database,"ACTIVE");
            store.jobProgress(id,"SUCCEEDED","完成","可用原用户名和密码连接网关；请在字段策略页复核推荐策略。");
        } catch(Exception e) {
            String message=Errors.explain(e);
            store.saveLogicalDatabase(connectionId,database,database,unit,"FAILED",stage+"："+message);
            access.bind(connectionId,database,"FAILED");
            store.jobProgress(id,"FAILED",stage,message);
            try { accounts.synchronize(); } catch(Exception ignored) { /* Remains fail-closed by inactive mapping. */ }
        } finally { inFlight.remove(database); }
    }
    private void progress(String id,String stage) { store.jobProgress(id,"RUNNING",stage,"正在执行，请勿重复提交。刷新页面不会中断任务。"); }

    public synchronized void detach(String database) throws Exception {
        if(inFlight.containsKey(database)) throw new IllegalStateException("接入正在执行，请等待任务结束。");
        Map<String,Object> mapping=store.anyMapping(database);
        if(mapping.isEmpty()) return;
        // In Proxy, DROP DATABASE removes the logical database and repository configuration, not Doris data.
        proxy.controlSql(null,"DROP DATABASE IF EXISTS "+identifier(database));
        store.removeMapping(database);access.removeDatabase(database);accounts.synchronize();
    }
    public synchronized void deleteConnection(long id) {
        if(access.dependencies(id)>0) throw new IllegalStateException("账号仍关联数据库，请先解除账号关联。");
        store.deleteConnection(id);
    }
    public synchronized void updateConnection(long id,Map<String,Object> request) {
        if(access.dependencies(id)>0) {
            Map<String,Object> old=store.connection(id);
            if(!AccountAccessService.sameEndpoint(old,request) || !String.valueOf(old.get("username")).equals(String.valueOf(request.get("username")))
                    || (request.get("password")!=null && !String.valueOf(request.get("password")).isEmpty() && !store.connectionPassword(id).equals(String.valueOf(request.get("password")))))
                throw new IllegalStateException("已关联账号不能直接替换连接身份或密码，请先解除关联。");
        }
        store.updateConnection(id,request);
    }
    static String identifier(String s) { return "`"+s.replace("`","``")+"`"; }
    static String literal(String s) { return "'"+s.replace("\\","\\\\").replace("'","''")+"'"; }
}
