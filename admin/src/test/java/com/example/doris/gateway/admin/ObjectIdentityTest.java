package com.example.doris.gateway.admin;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectIdentityTest {
 JdbcTemplate jdbc;AdminStore store;ObjectIdentityService identities;Map<String,String> physical;
 @BeforeEach void setup(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE","sa","");jdbc=new JdbcTemplate(ds);
  store=new AdminStore(jdbc,new ObjectMapper(),null);store.initialize();store.saveLogicalDatabase(1,"db","db","unit","ACTIVE","");
  store.upsertManagedObject(1,"db","src","TABLE","ACTIVE",null,"");
  physical=new HashMap<>();physical.put("src","0:10:20");
  ObjectIdentityProvider provider=(c,d,t)->{if(!physical.containsKey(t))throw new java.sql.SQLException("metadata unavailable");return physical.get(t);};
  ProxyFactory proxy=new ProxyFactory(new ObjectIdentityService(jdbc,store,provider));proxy.setProxyTargetClass(true);
  proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
  identities=(ObjectIdentityService)proxy.getProxy();identities.initialize();
 }
 void enable()throws Exception{identities.confirm("db",identities.preview("db"));}
 void discover(){store.discoverManagedObject(1,"db","external","TABLE");physical.put("external","0:10:50");}
 long externalId(){return ((Number)store.managedObject(1,"db","external").get("id")).longValue();}
 @Test void externalSourceConfirmationDoesNotActivateTable()throws Exception{
  enable();discover();Map<String,Object> preview=identities.previewNewSource(externalId());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM physical_object_bindings",Integer.class));
  identities.confirmNewSource(externalId(),preview);identities.require(1,"db","external");
  assertFalse(store.isActiveManagedObject(1,"db","external"));
  assertThrows(IllegalStateException.class,()->identities.confirmNewSource(externalId(),preview));
 }
 @Test void externalSourceRejectsPhysicalAndGenerationChanges()throws Exception{
  enable();discover();Map<String,Object> preview=identities.previewNewSource(externalId());
  physical.put("external","0:10:51");assertThrows(IllegalStateException.class,()->identities.confirmNewSource(externalId(),preview));
  physical.put("external","0:10:50");jdbc.update("UPDATE managed_objects SET generation_id='changed' WHERE id=?",externalId());
  assertThrows(IllegalStateException.class,()->identities.confirmNewSource(externalId(),preview));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM physical_object_bindings",Integer.class));
 }
 @Test void externalSourceRejectsOldLineageAndPendingTask()throws Exception{
  enable();discover();
  jdbc.update("INSERT INTO lineage_edges(event_id,source_database,source_table,created_at) VALUES('old','db','external','now')");
  assertThrows(IllegalStateException.class,()->identities.previewNewSource(externalId()));
  jdbc.update("DELETE FROM lineage_edges WHERE event_id='old'");
  jdbc.update("UPDATE managed_objects SET pending_execution_id='unfinished' WHERE id=?",externalId());
  assertThrows(IllegalStateException.class,()->identities.previewNewSource(externalId()));
 }
 @Test void failedNewSourceApprovalStaysBlockedAndCanBeRetried()throws Exception{
  enable();discover();identities.confirmNewSource(externalId(),identities.previewNewSource(externalId()));
  DorisMetadataService metadata=org.mockito.Mockito.mock(DorisMetadataService.class);
  DistSqlService policies=org.mockito.Mockito.mock(DistSqlService.class);
  org.mockito.Mockito.when(metadata.scanTable(1,"db","external")).thenThrow(new java.sql.SQLException("injected outage")).thenReturn(1);
  ManagedSqlExecutionService service=new ManagedSqlExecutionService(store,new SqlLineageAnalyzer(store),metadata,policies,"test-token",identities);
  assertThrows(IllegalStateException.class,()->service.approveManagedObject(externalId()));
  assertEquals("UNMANAGED",store.managedObject(externalId()).get("status"));
  assertFalse(store.isActiveManagedObject(1,"db","external"));
  service.approveManagedObject(externalId());assertTrue(store.isActiveManagedObject(1,"db","external"));
 }
 @Test void previewDoesNotSilentlyEnableOrWriteBaseline()throws Exception{
  identities.preview("db");assertFalse(identities.enabled(1,"db"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM physical_object_bindings",Integer.class));
  enable();identities.require(1,"db","src");identities.initialize();assertTrue(identities.enabled(1,"db"));identities.require(1,"db","src");
 }
 @Test void stalePreviewCannotBaselineReplacement()throws Exception{
  List<Map<String,Object>> preview=identities.preview("db");physical.put("src","0:10:21");
  assertThrows(IllegalStateException.class,()->identities.confirm("db",preview));assertFalse(identities.enabled(1,"db"));
 }
 @Test void replacementQuarantineCannotBeClearedByScanOrConfirmation()throws Exception{
  enable();physical.put("src","0:10:99");assertThrows(IllegalStateException.class,()->identities.require(1,"db","src"));
  store.upsertManagedObject(1,"db","src","TABLE","ACTIVE",null,"scanned");
  assertThrows(IllegalStateException.class,()->identities.confirm("db",identities.preview("db")));
  physical.put("src","0:10:20");assertThrows(IllegalStateException.class,()->identities.require(1,"db","src"));
 }
 @Test void ordinaryApprovalCannotAdoptReplacementOrChangeRegistryStatus()throws Exception{
  enable();physical.put("src","0:10:99");
  store.upsertManagedObject(1,"db","src","TABLE","DRIFTED",null,"schema changed");
  ManagedSqlExecutionService service=new ManagedSqlExecutionService(store,new SqlLineageAnalyzer(store),null,null,"test-token",identities);
  long id=((Number)store.managedObject(1,"db","src").get("id")).longValue();
  assertThrows(IllegalStateException.class,()->service.approveManagedObject(id));
  assertEquals("DRIFTED",store.managedObject(id).get("status"));
  assertThrows(IllegalStateException.class,()->identities.require(1,"db","src"));
 }
 @Test void checkedTransportFailureRollsBackEarlierBaselineRows()throws Exception{
  store.upsertManagedObject(1,"db","other","TABLE","ACTIVE",null,"");physical.put("other","0:10:21");
  List<Map<String,Object>> preview=identities.preview("db");physical.remove("other");
  assertThrows(java.sql.SQLException.class,()->identities.confirm("db",preview));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM physical_object_bindings",Integer.class));assertFalse(identities.enabled(1,"db"));
 }
 @Test void metadataOutageNeverFallsBackToNameAndDoesNotRewriteBaseline()throws Exception{
  enable();physical.remove("src");assertThrows(java.sql.SQLException.class,()->identities.require(1,"db","src"));
  assertEquals("0:10:20",jdbc.queryForObject("SELECT physical_identity FROM physical_object_bindings",String.class));
 }
 @Test void renameKeepsPhysicalIdentityButRejectsDifferentTarget()throws Exception{
  enable();store.reserveManagedExecution("rename",1,"unit","db","db","RENAME","src","renamed","sql","TABLE");store.acknowledgeExecution("rename");
  physical.put("renamed",physical.remove("src"));identities.verifyExecution(store.managedExecution("rename"));
  store.applyRenameRegistry("rename");identities.verifyExecution(store.managedExecution("rename"));
  physical.put("renamed","0:10:99");assertThrows(IllegalStateException.class,()->identities.verifyExecution(store.managedExecution("rename")));
 }
 @Test void firstConfirmedCreateBindsIdentityAndRecoveryCannotAdoptReplacement()throws Exception{
  enable();store.reserveManagedExecution("create",1,"unit","db","db","CTAS","newtable","","sql","TABLE");store.acknowledgeExecution("create");
  physical.put("newtable","0:10:30");identities.verifyExecution(store.managedExecution("create"));
  store.finishOwnedExecution("create","FAILED","injected");store.acknowledgeExecution("create");physical.put("newtable","0:10:31");
  assertThrows(IllegalStateException.class,()->identities.verifyExecution(store.managedExecution("create")));
 }
 @Test void interruptedCreateWithoutCapturedIdentityDoesNotGuessOnRecovery()throws Exception{
  enable();store.reserveManagedExecution("create",1,"unit","db","db","CTAS","newtable","","sql","TABLE");store.acknowledgeExecution("create");
  store.finishOwnedExecution("create","FAILED","injected");store.acknowledgeExecution("create");physical.put("newtable","0:10:31");
  assertThrows(IllegalStateException.class,()->identities.verifyExecution(store.managedExecution("create")));
 }
}
