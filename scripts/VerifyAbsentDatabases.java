import java.sql.*;
import com.example.doris.gateway.admin.SecretCodec;
/** Read-only cold backup inspection plus direct Doris USE; does not print secrets. */
public class VerifyAbsentDatabases {
 public static void main(String[] args)throws Exception {
  SecretCodec secrets=new SecretCodec(System.getenv("ADMIN_MASTER_KEY_B64"));
  try(Connection h=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=REGULAR;DATABASE_TO_UPPER=FALSE","sa",System.getenv().getOrDefault("ADMIN_DB_PASSWORD",""))) {
   for(String name:new String[]{"DWH_人口","DWH_入口"}) {
    try(PreparedStatement p=h.prepareStatement("SELECT c.host,c.port,c.username,c.password_enc,l.physical_database FROM logical_databases l JOIN connections c ON l.connection_id=c.id WHERE l.logical_name=?")) {
     p.setString(1,name);try(ResultSet r=p.executeQuery()) {
      if(!r.next())throw new IllegalStateException("Missing mapping: "+name);
      String physical=r.getString(5);if(!name.equals(physical))throw new IllegalStateException("Unexpected physical target");
      String url="jdbc:mysql://"+r.getString(1)+":"+r.getInt(2)+"/?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=10000";
      try(Connection d=DriverManager.getConnection(url,r.getString(3),secrets.decrypt(r.getString(4)));Statement s=d.createStatement()) {
       try{s.execute("USE `"+physical.replace("`","``")+"`");throw new IllegalStateException("Database exists; refusing automatic detach: "+name);}
       catch(SQLException e){if(e.getErrorCode()!=1049)throw new IllegalStateException("Absence not proven for "+name+"; SQL code="+e.getErrorCode());}
       System.out.println("VERIFIED absent via saved connection, Doris error 1049: "+name);
      }
     }
    }
   }
  }
 }
}
