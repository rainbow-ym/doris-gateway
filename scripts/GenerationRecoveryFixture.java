import java.sql.*;
/** Cold isolated H2 fixture only. Never include in runtime images. */
public class GenerationRecoveryFixture {
 public static void main(String[] args)throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:h2:file:/data/gateway-recovery-test-20261016/admin-data/gateway-admin;IFEXISTS=TRUE;AUTO_SERVER=FALSE;MODE=MySQL;DATABASE_TO_UPPER=FALSE","sa",System.getenv().getOrDefault("ADMIN_DB_PASSWORD",""))) {
   c.setAutoCommit(false);
   String id;
   try(PreparedStatement p=c.prepareStatement("SELECT e.execution_id FROM managed_sql_executions e JOIN managed_objects o ON e.connection_id=o.connection_id AND e.physical_database=o.database_name AND e.target_table=o.table_name WHERE e.physical_database='gateway_multi_acceptance_20261014' AND e.target_table='generation_recovery_fixture' AND e.statement_type='CTAS' AND e.status='COMMITTED' AND e.target_generation<>o.generation_id ORDER BY e.id")) {
    try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("No isolated old generation found");id=r.getString(1);}
   }
   try(PreparedStatement p=c.prepareStatement("UPDATE managed_sql_executions SET status='FAILED',success_received=TRUE,message='GENERATION_RECOVERY_FIXTURE' WHERE execution_id=?")){p.setString(1,id);if(p.executeUpdate()!=1)throw new IllegalStateException();}
   c.commit();System.out.println("Injected isolated old-generation retry task.");
  }
 }
}
