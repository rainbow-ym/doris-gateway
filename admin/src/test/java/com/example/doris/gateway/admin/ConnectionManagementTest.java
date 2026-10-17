package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionManagementTest {
    private AdminStore store;
    @BeforeEach void setup(){store=new AdminStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false","sa","")),new ObjectMapper(),new SecretCodec(Base64.getEncoder().encodeToString(new byte[32])));store.initialize();}
    private Map<String,Object> input(String name){Map<String,Object> m=new LinkedHashMap<>();m.put("name",name);m.put("host","127.0.0.1");m.put("port",9030);m.put("username","tester");m.put("password","secret");m.put("databasePattern","test%");m.put("tablePattern","%");return m;}
    @Test void preventsDuplicateNameAndEndpoint(){store.saveConnection(input("source"));Map<String,Object> duplicateName=input(" SOURCE ");duplicateName.put("databasePattern","different%");assertThrows(IllegalArgumentException.class,()->store.saveConnection(duplicateName));assertThrows(IllegalArgumentException.class,()->store.saveConnection(input("other")));}
    @Test void preventsConflictingPasswordsForMirroredUser(){store.saveConnection(input("source"));Map<String,Object> m=input("other");m.put("databasePattern","other%");m.put("password","different");assertThrows(IllegalArgumentException.class,()->store.saveConnection(m));}
    @Test void editsAndDeletesUnusedConnection(){long id=store.saveConnection(input("source"));Map<String,Object> m=input("renamed");m.put("password","");store.updateConnection(id,m);assertEquals("secret",store.connectionPassword(id));assertEquals("renamed",store.connections().get(0).get("name"));store.deleteConnection(id);assertTrue(store.connections().isEmpty());}
    @Test void protectsDependenciesAndCredentialIdentity(){long id=store.saveConnection(input("source"));store.saveLogicalDatabase(id,"test","test","ds","ACTIVE","");assertThrows(IllegalStateException.class,()->store.deleteConnection(id));Map<String,Object> m=input("source");m.put("host","other");assertThrows(IllegalStateException.class,()->store.updateConnection(id,m));}
    @Test void policiesAreIsolatedByConnection(){long first=store.saveConnection(input("one"));Map<String,Object> second=input("two");second.put("host","127.0.0.2");long other=store.saveConnection(second);store.upsertColumn(first,"db","t","id_card","VARCHAR","","ID_CARD");store.upsertColumn(other,"db","other_table","id_card","VARCHAR","","ID_CARD");store.confirmRecommendations(first);store.confirmRecommendations(other);store.saveLogicalDatabase(first,"logical","db","ds","SYNCING","");assertEquals(1,store.confirmedForLogicalDatabase("logical").size());}
    @Test void interruptedJobsExplainRecovery(){long id=store.saveConnection(input("source"));store.newJob("job",id,"db");store.initialize();assertEquals("FAILED",store.jobs().get(0).get("status"));assertTrue(store.jobs().get(0).get("message").toString().contains("重试"));}
}
