package com.example.doris.gateway.admin;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountLifecycleStateTest {
 private JdbcTemplate jdbc;private AccountAccessStore access;private AccountLifecycleService service;private AdminStore store;private GatewayAccounts accounts;
 @BeforeEach void setup(){
  jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false","sa",""));
  access=new AccountAccessStore(jdbc);access.initialize();store=mock(AdminStore.class);accounts=mock(GatewayAccounts.class);
  service=new AccountLifecycleService(jdbc,store,new SecretCodec(Base64.getEncoder().encodeToString(new byte[32])),mock(AccountAccessService.class),access,accounts,mock(DorisMetadataService.class));service.initialize();
 }
 @Test void activePendingDisabledEpoch(){
  assertEquals(0,access.lifecycleEpoch("u"));access.lifecycle("u","SYNC_PENDING",true);
  assertThrows(SecurityException.class,()->access.lifecycleEpoch("u"));
  access.lifecycle("u","ACTIVE",false);assertEquals(1,access.lifecycleEpoch("u"));
  access.lifecycle("u","DISABLED",true);assertFalse(access.lifecycleActive("u"));
  access.lifecycle("u","ACTIVE",false);assertEquals(2,access.lifecycleEpoch("u"));
 }
 @Test void restartQuarantinesUnknownAndNeverReplays(){
  jdbc.update("INSERT INTO account_lifecycle_tasks(id,username,operation,status) VALUES('uncertain','u','CREATE','EXECUTING')");
  service.initialize();assertEquals("UNKNOWN",service.task("uncertain").get("status"));
  assertFalse(access.lifecycleActive("u"));service.retry();service.synchronize("uncertain");verifyNoInteractions(store,accounts);
 }
 @Test void endpointRefusesNonRootBeforeDatabaseAccess(){
  assertThrows(SecurityException.class,()->service.execute("user","db","CREATE USER target IDENTIFIED BY 'secret'"));
  assertTrue(service.tasks().isEmpty());verifyNoInteractions(store,accounts);
 }
 @Test void taskResponsesNeverContainEncryptedOrPlainPasswords(){
  jdbc.update("INSERT INTO account_lifecycle_tasks(id,username,operation,status,password_enc) VALUES('secret','u','CREATE','NEEDS_CREDENTIALS','encrypted-secret')");
  assertFalse(service.tasks().toString().contains("encrypted-secret"));assertFalse(service.task("secret").containsKey("password_enc"));
 }
}
