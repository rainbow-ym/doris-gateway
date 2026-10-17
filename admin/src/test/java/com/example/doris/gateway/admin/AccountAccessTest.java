package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountAccessTest {
    private AdminStore store;private AccountAccessStore access;private AccountAccessService service;
    private long owner,b,c;
    @BeforeEach void setup() {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false","sa",""));
        store=new AdminStore(jdbc,new ObjectMapper(),new SecretCodec(Base64.getEncoder().encodeToString(new byte[32])));store.initialize();
        access=new AccountAccessStore(jdbc);access.initialize();
        DistSqlService proxy=mock(DistSqlService.class);when(proxy.controlUsername()).thenReturn("control");
        service=new AccountAccessService(store,access,new PolicyCatalog(),proxy,"test-token-longer-than-24-characters");
        owner=source("root");b=source("B");c=source("C");
        store.saveLogicalDatabase(owner,"DWD_ZZ","DWD_ZZ","ds","ACTIVE","");
        store.upsertManagedObject(owner,"DWD_ZZ","ZZ","TABLE","ACTIVE",null,"");
        access.migrate(store);access.bind(b,"DWD_ZZ","ACTIVE");access.bind(c,"DWD_ZZ","ACTIVE");
    }
    private long source(String user) {
        Map<String,Object> row=new HashMap<>();row.put("name",user);row.put("host","127.0.0.1");row.put("port",9030);row.put("username",user);row.put("password",user+"-password");return store.saveConnection(row);
    }
    @Test void unicodeSourceIsStoredExactly(){long id=source("中文用户_01");assertEquals("中文用户_01",store.connection(id).get("username"));}
    @Test void resolvesEachUsersCredentialsAndFailsClosedWithoutBinding()throws Exception {
        assertEquals("B",service.resolve("B","dwd_zz").get("username"));
        assertEquals("B-password",service.resolve("B","DWD_ZZ").get("password"));
        assertThrows(SecurityException.class,()->service.resolve("unknown","DWD_ZZ"));
        access.unbind(b,"DWD_ZZ");assertThrows(SecurityException.class,()->service.resolve("B","DWD_ZZ"));
    }
    @Test void migrationIsIdempotentAndPoliciesAreIndependent() {
        access.migrate(store);assertEquals(3,access.bindings().size());
        Map<String,Object> p=new HashMap<>();p.put("username","B");p.put("logicalName","DWD_ZZ");p.put("sensitiveType","ID_CARD");p.put("action","CLEAR");service.savePolicy(p);
        assertEquals(1,access.policies("B","DWD_ZZ").size());assertTrue(access.policies("C","DWD_ZZ").isEmpty());
        assertEquals(1,store.managedObjects().size());
    }
    @Test void databaseScopeCoversNewTableButOtherTableScopeDoesNot() {
        Map<String,Object> bg=new HashMap<>();bg.put("DatabasePrivs","internal.DWD_ZZ: Select_priv");
        Map<String,Object> cg=new HashMap<>();cg.put("TablePrivs","internal.DWD_ZZ.AA: Select_priv");
        access.replaceScopes(b,DorisPrivilegeSnapshot.parse(Collections.singletonList(bg)));
        access.replaceScopes(c,DorisPrivilegeSnapshot.parse(Collections.singletonList(cg)));
        service.project("DWD_ZZ");
        assertTrue(access.accessRows(b).stream().anyMatch(r->"SELECT_PRIV".equals(r.get("privilege"))&&"ALLOWED".equals(r.get("state"))));
        assertFalse(access.accessRows(c).stream().anyMatch(r->"ALLOWED".equals(r.get("state"))));
        store.dropManagedObject(owner,"DWD_ZZ","ZZ","drop-test");service.project("DWD_ZZ");
        assertTrue(access.accessRows(b).stream().allMatch(r->"DENIED".equals(r.get("state"))));
    }
    @Test void unknownPrivilegeFormatsNeverGrantAccess() {
        Map<String,Object> r=new HashMap<>();r.put("DatabasePrivs","unexpected::DWD_ZZ: Select_priv");r.put("ColPrivs","internal.DWD_ZZ.ZZ: Select_priv(id)");
        assertTrue(DorisPrivilegeSnapshot.parse(Collections.singletonList(r)).isEmpty());
    }
    @Test void internalTokenCannotBeEmptyOrForged() {
        assertThrows(SecurityException.class,()->service.authenticate(null));
        assertThrows(SecurityException.class,()->service.authenticate("wrong"));
        service.authenticate("test-token-longer-than-24-characters");
    }
}
