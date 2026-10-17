import java.sql.*;
import java.time.Instant;

/** Offline fault injection exclusively into a cold isolated test database copy. Never ship in the image. */
public class PublicationRecoveryFixture {
    public static void main(String[] args) throws Exception {
        String file="/data/gateway-recovery-test-20261016/admin-data/gateway-admin";
        try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+file+";IFEXISTS=TRUE;AUTO_SERVER=FALSE;MODE=MySQL;DATABASE_TO_UPPER=FALSE","sa",System.getenv().getOrDefault("ADMIN_DB_PASSWORD",""))) {
            c.setAutoCommit(false);
            String db="gateway_multi_acceptance_20261014",snapshot,rules;
            try(PreparedStatement p=c.prepareStatement("SELECT a.snapshot_json,v.desired_json FROM active_policy_snapshots a JOIN publish_versions v ON v.id=a.version_id WHERE a.database_name=?")) {
                p.setString(1,db);try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("No isolated active snapshot");snapshot=r.getString(1);rules=r.getString(2);}
            }
            try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM policy_publications WHERE database_name=? AND status IN ('APPLYING','RECOVERY_REQUIRED','RECOVERING')")) {
                p.setString(1,db);try(ResultSet r=p.executeQuery()){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("An unfinished task already exists; refusing duplicate injection");}
            }
            long id;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO publish_versions(database_name,before_json,desired_json,statements_json,status,message,created_at) VALUES(?,?,?,'[]','APPLYING','RECOVERY_FIXTURE',?)",Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1,db);p.setString(2,rules);p.setString(3,rules);p.setString(4,Instant.now().toString());p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();id=r.getLong(1);}
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO policy_publications(version_id,database_name,before_json,snapshot_json,status) VALUES(?,?,?,?,'APPLYING')")) {
                p.setLong(1,id);p.setString(2,db);p.setString(3,snapshot);p.setString(4,snapshot);p.executeUpdate();
            }
            c.commit();System.out.println("Injected isolated pending publication: "+id);
        }
    }
}
