package com.example.doris.gateway.admin;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class InitializationService {
    private final JdbcTemplate jdbc;private final SecretCodec secrets;private final ObjectMapper json;private final AdminStore store;
    private final DorisMetadataService metadata;private final OnboardingService onboarding;private final AccountAccessService access;private final ManagedSqlExecutionService managed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public InitializationService(JdbcTemplate jdbc,SecretCodec secrets,ObjectMapper json,AdminStore store,DorisMetadataService metadata,OnboardingService onboarding,AccountAccessService access,ManagedSqlExecutionService managed){
        this.jdbc=jdbc;this.secrets=secrets;this.json=json;this.store=store;this.metadata=metadata;this.onboarding=onboarding;this.access=access;this.managed=managed;
    }
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS initialization_tasks(id VARCHAR(36) PRIMARY KEY,kind VARCHAR(32),status VARCHAR(32),created_at VARCHAR(64),message VARCHAR(1024))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS initialization_items(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(36),username VARCHAR(128),payload_enc CLOB,databases_json CLOB,connection_id BIGINT,status VARCHAR(32),message VARCHAR(1024))");
        jdbc.update("UPDATE initialization_items SET status='FAILED',message='服务重启中断了任务。校验阶段请重新上传；已保存账号可重试初始化。' WHERE status IN ('VALIDATING','RUNNING')");
        jdbc.update("UPDATE initialization_tasks SET status='FAILED',message='服务重启中断任务，请查看明细。' WHERE status IN ('VALIDATING','RUNNING')");
    }
    @PreDestroy public void stop(){worker.shutdownNow();}
    @Scheduled(fixedDelay=600000) public void expire(){jdbc.update("UPDATE initialization_items SET payload_enc=NULL,status='EXPIRED',message='未确认的凭据已过期，请重新上传。' WHERE connection_id IS NULL AND status IN ('READY','FAILED') AND task_id IN (SELECT id FROM initialization_tasks WHERE created_at<?)",Instant.now().minusSeconds(3600).toString());}
    public String preview(Map<String,Object> request)throws Exception{
        List<Map<String,String>> rows=AccountCsv.read(String.valueOf(request.get("csv")));
        String host=String.valueOf(request.get("host"));int port=Integer.parseInt(String.valueOf(request.getOrDefault("port",9030)));
        if(!host.matches("[A-Za-z0-9_.:-]+")||port<1||port>65535)throw new IllegalArgumentException("请输入有效的 Doris 地址和端口。");
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO initialization_tasks VALUES(?,'ACCOUNTS','VALIDATING',?,'正在验证凭据及可见数据库；不会授予 Doris 权限。')",id,Instant.now().toString());
        for(Map<String,String> row:rows){Map<String,Object> payload=new LinkedHashMap<>(row);payload.put("host",host);payload.put("port",port);payload.put("tablePattern","%");payload.put("name","导入-"+row.get("username"));
            jdbc.update("INSERT INTO initialization_items(task_id,username,payload_enc,status) VALUES(?,?,?,'VALIDATING')",id,row.get("username"),secrets.encrypt(json.writeValueAsString(payload)));}
        worker.submit(()->validate(id));store.audit("INITIALIZATION_PREVIEW","task="+id+",count="+rows.size());return id;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> payload(Map<String,Object> row)throws Exception{return json.readValue(secrets.decrypt(String.valueOf(row.get("payload_enc"))),Map.class);}
    private void validate(String task){
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM initialization_items WHERE task_id=? ORDER BY id",task)){
            long id=((Number)row.get("id")).longValue();
            try{
                Map<String,Object> p=payload(row);List<String> databases=new ArrayList<>();
                try(Connection c=DriverManager.getConnection(AccountAccessService.url(p,"information_schema"),String.valueOf(p.get("username")),String.valueOf(p.get("password")));
                    PreparedStatement s=c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE ? AND LOWER(SCHEMA_NAME) NOT IN ('information_schema','mysql','__internal_schema','sys','performance_schema') ORDER BY SCHEMA_NAME")){
                    s.setString(1,String.valueOf(p.get("databasePattern")));try(ResultSet rs=s.executeQuery()){while(rs.next())databases.add(rs.getString(1));}
                }
                if(databases.isEmpty())throw new IllegalArgumentException("未发现可接入的非系统数据库，请检查范围和 Doris 权限。");
                jdbc.update("UPDATE initialization_items SET databases_json=?,status='READY',message=? WHERE id=?",json.writeValueAsString(databases),"凭据有效；发现 "+databases.size()+" 个数据库，等待确认。",id);
            }catch(Exception e){jdbc.update("UPDATE initialization_items SET payload_enc=NULL,status='FAILED',message=? WHERE id=?",e instanceof IllegalArgumentException?e.getMessage():"连接验证失败，请核对地址、凭据及权限后重新上传。",id);}
        }
        jdbc.update("UPDATE initialization_tasks SET status='READY',message='预览完成；确认后只处理有效账号。未确认凭据一小时后清理。' WHERE id=?",task);
    }
    public synchronized void confirm(String task){
        int n=jdbc.update("UPDATE initialization_tasks SET status='RUNNING',message='正在接入，仅处理待确认或可重试项。' WHERE id=? AND status IN ('READY','FAILED','PARTIAL')",task);
        if(n!=1)throw new IllegalArgumentException("任务不存在或正在执行，请勿重复提交。");
        worker.submit(()->execute(task));
    }
    @SuppressWarnings("unchecked") private void execute(String task){
        for(Map<String,Object> item:jdbc.queryForList("SELECT * FROM initialization_items WHERE task_id=? AND (status='READY' OR (status='FAILED' AND connection_id IS NOT NULL)) ORDER BY id",task)){
            long itemId=((Number)item.get("id")).longValue();jdbc.update("UPDATE initialization_items SET status='RUNNING',message='正在同步账号及数据库。' WHERE id=?",itemId);
            try{
                long connectionId;
                if(item.get("connection_id")!=null)connectionId=((Number)item.get("connection_id")).longValue();
                else{
                    Map<String,Object> p=payload(item);Long existing=null;
                    for(Map<String,Object> c:store.connections())if(c.get("username").equals(p.get("username"))&&AccountAccessService.sameEndpoint(c,p)){
                        long cid=((Number)c.get("id")).longValue();if(!store.connectionPassword(cid).equals(p.get("password")))throw new IllegalArgumentException("已有同名账号的密码不同，未覆盖，请先处理账号凭据。");
                        if(!c.get("databasePattern").equals(p.get("databasePattern")))throw new IllegalArgumentException("已有账号扫描范围不同，请编辑原连接后再接入，避免扩大范围。");existing=cid;break;
                    }
                    connectionId=existing==null?store.saveConnection(p):existing;
                    jdbc.update("UPDATE initialization_items SET connection_id=?,payload_enc=NULL WHERE id=?",connectionId,itemId);
                }
                List<String> databases=json.readValue(String.valueOf(item.get("databases_json")),List.class);
                for(String db:databases){
                    List<String> jobs=onboarding.submit(connectionId,Collections.singletonList(db));
                    long deadline=System.currentTimeMillis()+300000;
                    while(true){
                        boolean pending=false;
                        for(String job:jobs){String state=jdbc.queryForObject("SELECT status FROM onboarding_jobs WHERE id=?",String.class,job);
                            if("FAILED".equals(state))throw new IllegalStateException("数据库接入失败："+db+"，请在接入任务中查看原因。");
                            if(!"SUCCEEDED".equals(state))pending=true;
                        }
                        Map<String,Object> m=store.anyMapping(db);
                        if(!pending&&"ACTIVE".equals(m.get("status"))){onboarding.submit(connectionId,Collections.singletonList(db));break;}
                        if(System.currentTimeMillis()>deadline)throw new IllegalStateException("等待数据库接入超时；未重放写入，可稍后重试。");Thread.sleep(250);
                    }
                }
                access.refresh(connectionId);jdbc.update("UPDATE initialization_items SET status='SUCCEEDED',message='账号关联与权限快照已完成。' WHERE id=?",itemId);
            }catch(Exception e){jdbc.update("UPDATE initialization_items SET status='FAILED',message=? WHERE id=?",e instanceof IllegalArgumentException||e instanceof IllegalStateException?e.getMessage():"初始化失败，请检查连接及接入任务后重试。",itemId);}
        }
        int failed=jdbc.queryForObject("SELECT COUNT(*) FROM initialization_items WHERE task_id=? AND status<>'SUCCEEDED'",Integer.class,task);
        jdbc.update("UPDATE initialization_tasks SET status=?,message=? WHERE id=?",failed==0?"SUCCEEDED":"PARTIAL",failed==0?"初始化完成。":"部分账号未完成，请查看明细；已成功账号不会重复创建。",task);
    }
    public List<Map<String,Object>> tasks(){return jdbc.queryForList("SELECT * FROM initialization_tasks ORDER BY created_at DESC LIMIT 100");}
    public List<Map<String,Object>> items(String task){return jdbc.queryForList("SELECT id,username,databases_json AS databases,connection_id AS connectionId,status,message FROM initialization_items WHERE task_id=? ORDER BY id",task);}
    public List<Map<String,Object>> approve(List<Long> ids){
        if(ids==null||ids.isEmpty()||ids.size()>50)throw new IllegalArgumentException("每次请选择 1—50 张表。");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:new LinkedHashSet<>(ids)){Map<String,Object> r=new LinkedHashMap<>();r.put("id",id);try{managed.approveManagedObject(id);r.put("status","SUCCEEDED");r.put("message","已纳管并更新相关用户的权限投影。");}catch(Exception e){r.put("status","FAILED");r.put("message",Errors.explain(e));}result.add(r);}return result;
    }
}
