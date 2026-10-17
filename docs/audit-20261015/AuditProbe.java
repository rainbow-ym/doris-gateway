import com.example.doris.gateway.admin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.Method;
import java.util.*;

// Isolated diagnostic: in-memory H2 only, no Doris or service endpoints.
public class AuditProbe {
  public static void main(String[] args) throws Exception {
    Method classify=ManagedSqlExecutionService.class.getDeclaredMethod("statementType",String.class);
    classify.setAccessible(true);
    System.out.println("PROBE literal classification="+classify.invoke(null,"SELECT 'information_schema.' AS note FROM secret_table"));
    System.out.println("PROBE mixed CTAS classification="+classify.invoke(null,"CREATE TABLE copied AS SELECT secret FROM src WHERE 'information_schema.' <> ''"));
    JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:audit;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE","sa",""));
    AdminStore store=new AdminStore(jdbc,new ObjectMapper(),null);store.initialize();
    store.saveLogicalDatabase(1,"auditdb","auditdb","auditunit","ACTIVE","");
    store.upsertColumn(1,"auditdb","src","secret","varchar","","ID_CARD");
    store.confirmRecommendations(1);
    store.upsertManagedObject(1,"auditdb","src","TABLE","ACTIVE",null,"");
    store.upsertManagedObject(1,"auditdb","v","VIEW","ACTIVE",null,"");
    SqlLineageAnalyzer analyzer=new SqlLineageAnalyzer(store);
    ManagedSqlExecutionService service=new ManagedSqlExecutionService(store,analyzer,null,null,"diagnostic-only-token-123456");
    Map<String,Object> req=new HashMap<>();req.put("storageUnit","auditunit");
    req.put("rawSql","CREATE OR REPLACE VIEW v AS SELECT secret AS innocent FROM src");
    System.out.println("PROBE replace existing view prepare="+service.prepare("diagnostic-only-token-123456",req).get("statementType"));
    try {
      SqlLineageAnalyzer.Analysis a=analyzer.analyze("CREATE TABLE scalar_copy AS SELECT (SELECT secret FROM src LIMIT 1) AS innocent",1,"auditdb");
      System.out.println("PROBE scalar-subquery edges="+a.edges.size());
    } catch(Exception e) {System.out.println("PROBE scalar-subquery rejected="+e.getClass().getSimpleName()+":"+e.getMessage());}
    Map<String,Object> event=new LinkedHashMap<>();event.put("eventId","atomicity-probe");
    Map<String,Object> edge=new HashMap<>();edge.put("sourceColumn",String.join("",Collections.nCopies(300,"x")));
    event.put("edges",Collections.singletonList(edge));
    try {store.saveLineageEvent(event);}catch(Exception e){System.out.println("PROBE edge insertion failed="+e.getClass().getSimpleName());}
    System.out.println("PROBE retry event inserted="+store.saveLineageEvent(event)+", persisted edges="+store.lineageEdges("atomicity-probe").size());
    PolicyCatalog.Policy aes=new PolicyCatalog().get("OUTPUT_AES_GCM");
    System.out.println("PROBE per-user AES catalog props="+aes.props);
  }
}
