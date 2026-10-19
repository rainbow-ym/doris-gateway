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
 @Test void grantsAndRevokesDoNotChangeCredentialEpoch(){
  access.beginLifecycle("u","CREATE");access.lifecycle("u","ACTIVE",false);
  long credential=access.lifecycleEpoch("u");
  for(String operation:Arrays.asList("GRANT","REVOKE")) {
   access.beginLifecycle("u",operation);assertFalse(access.lifecycleActive("u"));
   access.lifecycle("u","ACTIVE",false);assertEquals(credential,access.lifecycleEpoch("u"));
  }
  assertEquals(2,access.permissionEpoch("u"));
  access.beginLifecycle("u","PASSWORD");access.lifecycle("u","ACTIVE",false);
  assertEquals(credential+1,access.lifecycleEpoch("u"));assertEquals(2,access.permissionEpoch("u"));
  access.beginLifecycle("u","DROP");access.lifecycle("u","DISABLED",false);assertFalse(access.lifecycleActive("u"));
 }
 @Test void permissionRecoveryAcrossRestartDoesNotInvalidateCredentials(){
  access.lifecycle("u","ACTIVE",true);long credential=access.lifecycleEpoch("u");
  access.beginLifecycle("u","GRANT");
  jdbc.update("INSERT INTO account_lifecycle_tasks(id,username,operation,status) VALUES('grant','u','GRANT','EXECUTING')");
  service.initialize();service.initialize();
  assertEquals("UNKNOWN",service.task("grant").get("status"));assertFalse(access.lifecycleActive("u"));
  access.lifecycle("u","ACTIVE",false);assertEquals(credential,access.lifecycleEpoch("u"));assertEquals(1,access.permissionEpoch("u"));
 }
 @Test void legacyCredentialEpochSurvivesSchemaUpgrade(){
  jdbc.execute("DROP TABLE account_lifecycle_states");
  jdbc.execute("CREATE TABLE account_lifecycle_states(username VARCHAR(128) PRIMARY KEY,status VARCHAR(32),epoch BIGINT NOT NULL DEFAULT 0)");
  jdbc.update("INSERT INTO account_lifecycle_states VALUES('legacy','ACTIVE',17)");
  access.initialize();assertEquals(17,access.lifecycleEpoch("legacy"));assertEquals(0,access.permissionEpoch("legacy"));
  access.initialize();assertEquals(17,access.lifecycleEpoch("legacy"));
 }
 @Test void unknownOperationCannotSilentlyUseWrongInvalidationDomain(){
  assertThrows(IllegalArgumentException.class,()->access.beginLifecycle("u","UNKNOWN_KIND"));
  assertFalse(access.lifecycleManaged("u"));
 }
}
