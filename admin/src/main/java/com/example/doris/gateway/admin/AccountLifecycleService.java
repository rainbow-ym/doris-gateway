package com.example.doris.gateway.admin;

import com.example.doris.gateway.account.AccountCommand;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.PostConstruct;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Doris executes DCL once. Durable tasks retry synchronization only, never replay DCL. */
@Service
public class AccountLifecycleService {
 private final JdbcTemplate jdbc;private final AdminStore store;private final SecretCodec secrets;
 private final AccountAccessService permissions;private final AccountAccessStore access;private final GatewayAccounts accounts;
 private final DorisMetadataService metadata;
 public AccountLifecycleService(JdbcTemplate jdbc,AdminStore store,SecretCodec secrets,AccountAccessService permissions,
       AccountAccessStore access,GatewayAccounts accounts,DorisMetadataService metadata){
  this.jdbc=jdbc;this.store=store;this.secrets=secrets;this.permissions=permissions;this.access=access;this.accounts=accounts;this.metadata=metadata;
 }
 @PostConstruct public void initialize(){
  jdbc.execute("CREATE TABLE IF NOT EXISTS account_lifecycle_tasks(id VARCHAR(36) PRIMARY KEY,actor VARCHAR(128),username VARCHAR(128),operation VARCHAR(32),source_id BIGINT,password_enc CLOB,status VARCHAR(32),message VARCHAR(1024),created_at VARCHAR(64),updated_at VARCHAR(64))");
  jdbc.update("UPDATE account_lifecycle_tasks SET status='UNKNOWN',message='服务中断，数据库执行结果待核实；禁止自动重放 SQL。' WHERE status='EXECUTING'");
  // Uncertain credential operations remain fail-closed, including legacy crashes before invalidation.
  // Grant/revoke recovery must never invalidate authentication just because worker restarted.
  for(Map<String,Object> task:jdbc.queryForList("SELECT DISTINCT username,operation FROM account_lifecycle_tasks WHERE status IN ('UNKNOWN','SYNC_PENDING')"))
   access.lifecycle(String.valueOf(task.get("username")),"SYNC_PENDING",AccountChangeImpact.of(String.valueOf(task.get("operation"))).credentials);
 }
 public synchronized Map<String,Object> execute(String actor,String database,String sql)throws Exception{
  if(!"root".equals(actor))throw new SecurityException("当前自动账号管理仅开放给已接入的 Doris root，其他账号不代为执行。");
  AccountCommand cmd=AccountCommand.parse(sql);
  if(Arrays.asList("root","admin").contains(cmd.username)||cmd.username.equals(actor))throw new IllegalArgumentException("不能通过自动接入修改系统账号或当前执行账号。");
  Map<String,Object> selected=Collections.emptyMap();
  for(Map<String,Object> mapping:store.logicalDatabases())if(String.valueOf(mapping.get("logicalName")).equalsIgnoreCase(database))selected=store.anyMapping(String.valueOf(mapping.get("logicalName")));
  if(selected.isEmpty()||!"ACTIVE".equals(selected.get("status")))throw new IllegalArgumentException("请先选择一个已接入的数据库，再执行账号管理 SQL。");
  Map<String,Object> owner=store.connection(((Number)selected.get("connection_id")).longValue());
  List<Map<String,Object>> roots=new ArrayList<>();for(Map<String,Object> r:matching(actor))if(AccountAccessService.sameEndpoint(owner,r))roots.add(r);
  if(roots.isEmpty())throw new IllegalArgumentException("当前数据库端点未登记真实 root 凭据。");
  Map<String,Object> source=roots.get(0);long sourceId=id(source);
  for(Map<String,Object> r:roots)if(!AccountAccessService.sameEndpoint(source,r)||!store.connectionPassword(sourceId).equals(store.connectionPassword(id(r))))
   throw new IllegalArgumentException("root 存在冲突连接，无法唯一确定集群。");
  for(Map<String,Object> target:matching(cmd.username)){
   if("CREATE".equals(cmd.operation)&&cmd.conditional&&!store.connectionPassword(id(target)).equals(cmd.password))
    throw new IllegalArgumentException("该用户已接入且密码不同；IF NOT EXISTS 不会修改数据库密码，已拒绝覆盖，请使用明确的改密语句。");
   if(!AccountAccessService.sameEndpoint(source,target))throw new IllegalArgumentException("目标账号已有不同服务器的接入，不能自动覆盖。");
   if(("DROP".equals(cmd.operation)||"PASSWORD".equals(cmd.operation))&&store.dependencyCount(id(target))>0)
    throw new IllegalArgumentException("该账号负责共享元数据维护，请先迁移维护连接，不能直接删除或改密。");
  }
  if(jdbc.queryForObject("SELECT COUNT(*) FROM account_lifecycle_tasks WHERE username=? AND status IN ('EXECUTING','UNKNOWN','SYNC_PENDING','NEEDS_CREDENTIALS','NEEDS_ONBOARDING')",Integer.class,cmd.username)>0)
   throw new IllegalArgumentException("该用户有未完成同步任务，请先在账号同步任务中处理，勿重复执行。");
  String task=UUID.randomUUID().toString(),now=Instant.now().toString();
  jdbc.update("INSERT INTO account_lifecycle_tasks VALUES(?,?,?,?,?,?, 'EXECUTING','正在执行数据库账号操作',?,?)",
    task,actor,cmd.username,cmd.operation,sourceId,cmd.password==null?null:secrets.encrypt(cmd.password),now,now);
  boolean previouslyActive=access.lifecycleActive(cmd.username);
  access.beginLifecycle(cmd.username,cmd.operation);
  try(Connection c=DriverManager.getConnection(AccountAccessService.url(source,"information_schema"),actor,store.connectionPassword(sourceId));Statement s=c.createStatement()){
   s.setQueryTimeout(15);
   // Actual database authorization is mandatory; no pooled control/metadata identity.
   try(ResultSet identity=s.executeQuery("SELECT CURRENT_USER()")){
    identity.next();if(!identity.getString(1).matches(".*['\"]?root['\"]?@.*"))throw new SecurityException("后端匹配身份不是 root，已拒绝执行。");
   }
   s.execute(sql);
  }catch(SQLException e){
   boolean uncertain=e instanceof SQLTimeoutException||e.getSQLState()==null||e.getSQLState().startsWith("08");
   state(task,uncertain?"UNKNOWN":"FAILED",uncertain?"数据库结果不确定，需核实；不会自动重放 SQL。":"Doris 拒绝账号操作，错误码 "+e.getErrorCode()+"，SQLState "+e.getSQLState()+"；请核对语法和权限。");
   if(!uncertain){jdbc.update("UPDATE account_lifecycle_tasks SET password_enc=NULL WHERE id=?",task);access.lifecycle(cmd.username,previouslyActive?"ACTIVE":"DISABLED",false);}
   throw new IllegalStateException("账号操作未完成，任务 "+task+"；"+(uncertain?"结果待核实，勿重复执行。":"Doris 已拒绝，请查看任务。"));
  }catch(Exception e){state(task,"UNKNOWN","执行结果待核实，未自动重放。");throw new IllegalStateException("账号任务 "+task+" 执行结果待核实。");}
  state(task,"SYNC_PENDING","Doris 操作成功，正在同步网关。");
  synchronize(task);
  Map<String,Object> result=task(task);
  if(!"SUCCEEDED".equals(result.get("status")))throw new IllegalStateException("Doris 操作已成功，网关同步待处理；不要重复执行 SQL。任务 "+task+"："+result.get("message"));
  if(!"DROP".equals(cmd.operation))result.put("epoch",access.lifecycleEpoch(cmd.username));
  result.put("permissionEpoch",access.permissionEpoch(cmd.username));
  return result;
 }
 private List<Map<String,Object>> matching(String user){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> s:store.connections())if(user.equals(s.get("username")))result.add(s);return result;}
 private static long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
 private void state(String id,String status,String message){jdbc.update("UPDATE account_lifecycle_tasks SET status=?,message=?,updated_at=? WHERE id=?",status,message,Instant.now().toString(),id);}
 public List<Map<String,Object>> tasks(){return jdbc.queryForList("SELECT id,actor,username,operation,status,message,created_at AS createdAt,updated_at AS updatedAt FROM account_lifecycle_tasks ORDER BY created_at DESC LIMIT 200");}
 public Map<String,Object> task(String task){return jdbc.queryForMap("SELECT id,username,operation,status,message FROM account_lifecycle_tasks WHERE id=?",task);}
 public synchronized void reconcile(String task)throws Exception {
  Map<String,Object> t=jdbc.queryForMap("SELECT * FROM account_lifecycle_tasks WHERE id=?",task);
  if(!"UNKNOWN".equals(t.get("status")))throw new IllegalArgumentException("只有执行结果待核实的任务需要核实恢复。");
  if("DROP".equals(t.get("operation"))){
   long sourceId=((Number)t.get("source_id")).longValue();Map<String,Object> source=store.connection(sourceId);
   try(Connection c=DriverManager.getConnection(AccountAccessService.url(source,"information_schema"),"root",store.connectionPassword(sourceId));Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW ALL GRANTS")){
    while(r.next()){String identity=r.getString(1).replace("'","").replace("\"","");if(identity.startsWith(t.get("username")+"@"))throw new IllegalArgumentException("Doris 中仍存在该用户，不能确认删除。请核实真实数据库后再恢复；不会自动重放 DROP USER。");}
   }
  }
  state(task,"SYNC_PENDING","管理员请求按 Doris 当前实际身份和授权恢复，不重放原 SQL。");
  store.audit("ACCOUNT_LIFECYCLE_RECONCILED","task="+task);
  synchronize(task);
 }
 public synchronized void synchronize(String task){
  Map<String,Object> t=jdbc.queryForMap("SELECT * FROM account_lifecycle_tasks WHERE id=?",task);
  if(!Arrays.asList("SYNC_PENDING","NEEDS_CREDENTIALS","NEEDS_ONBOARDING").contains(t.get("status")))return;
  String user=String.valueOf(t.get("username")),op=String.valueOf(t.get("operation"));
  try{
   Map<String,Object> source=store.connection(((Number)t.get("source_id")).longValue());
   List<Map<String,Object>> targets=matching(user);
   if("DROP".equals(op)){
    access.lifecycle(user,"DISABLED",false);
    for(Map<String,Object> row:targets)jdbc.update("UPDATE account_database_bindings SET status='DISABLED' WHERE connection_id=?",id(row));
    jdbc.update("DELETE FROM user_output_policies WHERE username=?",user);
    accounts.synchronize();state(task,"SUCCEEDED","Doris 用户已删除；网关登录和业务访问已禁用。");return;
   }
   String password=t.get("password_enc")==null?null:secrets.decrypt(String.valueOf(t.get("password_enc")));
   if(password==null&&targets.isEmpty()){state(task,"NEEDS_CREDENTIALS","数据库授权已成功；请在平台登记该用户凭据后点击重试同步。");return;}
   // Validate supplied credentials: IF NOT EXISTS must not overwrite a real user's different password.
   String effective=password!=null?password:store.connectionPassword(id(targets.get(0)));
   try(Connection check=DriverManager.getConnection(AccountAccessService.url(source,"information_schema"),user,effective);Statement s=check.createStatement();ResultSet r=s.executeQuery("SELECT CURRENT_USER()")){
    r.next();String identity=r.getString(1).replace("'","").replace("\"","");
    if(!identity.equals(user+"@%"))throw new IllegalStateException("实际 Host 身份不同，不能自动合并。");
   }
   if(targets.isEmpty()){
    Map<String,Object> r=new LinkedHashMap<>();r.put("name","自动接入-"+user);r.put("host",source.get("host"));r.put("port",source.get("port"));
    r.put("username",user);r.put("password",effective);r.put("databasePattern","%");r.put("tablePattern","%");store.saveConnection(r);targets=matching(user);
   }else if(password!=null){
    for(Map<String,Object> row:targets)jdbc.update("UPDATE connections SET password_enc=? WHERE id=?",secrets.encrypt(password),id(row));
   }
   boolean pending=false;
   for(Map<String,Object> row:targets){
    long connectionId=id(row);Set<String> visible=new HashSet<>(metadata.databases(connectionId));
    // Remove stale associations without removing shared physical objects or lineage.
    for(Map<String,Object> binding:access.bindings())if(((Number)binding.get("connectionId")).longValue()==connectionId&&!visible.contains(binding.get("logicalName")))
     access.bind(connectionId,String.valueOf(binding.get("logicalName")),"DISABLED");
    for(String db:visible){
     Map<String,Object> mapping=store.anyMapping(db);
     if(mapping.isEmpty()||!"ACTIVE".equals(mapping.get("status"))){pending=true;continue;}
     Map<String,Object> owner=store.connection(((Number)mapping.get("connection_id")).longValue());
     if(!AccountAccessService.sameEndpoint(source,owner))throw new IllegalStateException("集群端点冲突");
     access.bind(connectionId,db,"ACTIVE");
    }
    permissions.refresh(connectionId);
   }
   access.lifecycle(user,"ACTIVE",false);accounts.synchronize();
   jdbc.update("UPDATE account_lifecycle_tasks SET password_enc=NULL WHERE id=?",task);
   state(task,pending?"NEEDS_ONBOARDING":"SUCCEEDED",pending?"账号及已纳管库授权已同步；存在未纳管数据库，请在初始化模块接入后重试。":"账号与已纳管库表权限同步完成；返回策略与共享血缘保持不变。");
  }catch(Exception e){access.lifecycle(user,"SYNC_PENDING",false);state(task,"SYNC_PENDING","数据库操作已成功，同步失败；请核对用户凭据、维护服务与接入状态后重试。不会重放数据库 SQL。");}
 }
 @Scheduled(fixedDelay=10000) public synchronized void retry(){
  for(String task:jdbc.queryForList("SELECT id FROM account_lifecycle_tasks WHERE status='SYNC_PENDING' ORDER BY created_at LIMIT 20",String.class))synchronize(task);
 }
}
