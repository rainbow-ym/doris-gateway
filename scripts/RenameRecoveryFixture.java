import com.example.doris.gateway.admin.AdminStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import java.util.*;

/** Cold isolated test fixture. Uses real registry transaction; never included in runtime. */
public class RenameRecoveryFixture {
 public static void main(String[] args) throws Exception {
  String mode=args[0],table=mode.equals("registry")?"rename_recovery_registry":"rename_recovery_policy";
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:file:/data/gateway-recovery-test-20261016/admin-data/gateway-admin;IFEXISTS=TRUE;AUTO_SERVER=FALSE;MODE=REGULAR;DATABASE_TO_UPPER=FALSE","sa",System.getenv().getOrDefault("ADMIN_DB_PASSWORD",""));
  JdbcTemplate jdbc=new JdbcTemplate(ds);
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM managed_sql_executions WHERE physical_database='gateway_multi_acceptance_20261014' AND target_table=? AND statement_type='RENAME' AND status=?",table,mode.equals("registry")?"PREPARED":"COMMITTED");
  if(rows.size()!=1)throw new IllegalStateException("Expected exactly one isolated fixture task");
  Map<String,Object> row=rows.get(0);String id=String.valueOf(row.get("execution_id"));
  ProxyFactory factory=new ProxyFactory(new AdminStore(jdbc,new ObjectMapper(),null));factory.setProxyTargetClass(true);
  factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
  AdminStore store=(AdminStore)factory.getProxy();
  if(mode.equals("registry")) {
   store.acknowledgeExecution(id);store.applyRenameRegistry(id);
   store.finishOwnedExecution(id,"FAILED","RENAME_REGISTRY_INTERRUPTION_FIXTURE");
  } else if(mode.equals("policy")) {
   if(((Number)row.get("rename_policy_version")).longValue()==0)throw new IllegalStateException("No committed rename policy marker");
   new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
    if(jdbc.update("UPDATE managed_objects SET status='SYNCING',pending_execution_id=? WHERE connection_id=? AND database_name=? AND table_name=? AND generation_id=? AND pending_execution_id IS NULL",id,row.get("connection_id"),row.get("physical_database"),table+"_new",row.get("target_generation"))!=1)throw new IllegalStateException("Fixture lost ownership");
    jdbc.update("UPDATE managed_sql_executions SET status='FAILED',message='RENAME_POLICY_INTERRUPTION_FIXTURE' WHERE execution_id=?",id);return null;
   });
  } else throw new IllegalArgumentException("Unsupported fixture stage");
  System.out.println("PASS isolated rename interruption injected: "+mode);
 }
}
