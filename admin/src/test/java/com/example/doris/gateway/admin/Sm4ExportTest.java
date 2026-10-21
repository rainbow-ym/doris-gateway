package com.example.doris.gateway.admin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Sm4ExportTest {
 @TempDir Path dir;
 Sm4ExportService service;JdbcTemplate jdbc;AccountAccessService access;Map<String,Object> account;byte[] key=new byte[16];
 @BeforeEach void setup()throws Exception{
  String url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE";
  jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));AdminStore store=new AdminStore(jdbc,new ObjectMapper(),null);store.initialize();
  jdbc.execute("CREATE TABLE src(secret VARCHAR(64))");
  store.saveLogicalDatabase(1,"PUBLIC","PUBLIC","unit","ACTIVE","");store.upsertManagedObject(1,"PUBLIC","src","TABLE","ACTIVE",null,"");
  access=mock(AccountAccessService.class);account=new LinkedHashMap<>();account.put("epoch",1L);account.put("username","sa");account.put("password","");account.put("url",url);
  when(access.resolve("user","db")).thenReturn(account);
  service=new Sm4ExportService(jdbc,store,access,new ObjectMapper());service.initialize();
  Files.write(dir.resolve("keys"),Collections.singletonList("v1="+Base64.getEncoder().encodeToString(key)));
  ReflectionTestUtils.setField(service,"keyFile",dir.resolve("keys").toString());ReflectionTestUtils.setField(service,"currentKey","v1");
  ObjectIdentityProvider identities=mock(ObjectIdentityProvider.class);when(identities.identity(1,"PUBLIC","src")).thenReturn("0:1:2");ReflectionTestUtils.setField(service,"identities",identities);
 }
 Map<String,Object> profile(boolean decrypt){Map<String,Object> p=new LinkedHashMap<>();p.put("username","user");p.put("databaseName","db");p.put("enabled",true);p.put("allowDecrypt",decrypt);p.put("maxRows",2);return p;}
 Map<String,Object> request(){
  Map<String,Object> p=new LinkedHashMap<>();p.put("username","user");p.put("database","db");p.put("labels",Arrays.asList("证件号码","备注"));p.put("displayed",Collections.singletonList(Arrays.asList("110101********1234","含,逗号\n和\"引号")));
  p.put("types",Arrays.asList(java.sql.Types.VARCHAR,java.sql.Types.VARCHAR));
  p.put("originals",Collections.singletonList(Collections.singletonMap("0","110101199001011234")));p.put("carrier",0);
  Map<String,String> source=new LinkedHashMap<>();source.put("database","PUBLIC");source.put("table","src");source.put("column","secret");p.put("sources",Collections.singletonList(source));return p;
 }
 String csv(String envelope){return "证件号码,备注\r\n110101********1234,\"含,逗号\n和\"\"引号\"\r\n"+envelope+",\r\n";}
 @Test void authenticatedRoundTripTamperAndWrongKey()throws Exception{
  byte[] plain="中文test".getBytes("UTF-8");String encrypted=Sm4ExportCodec.encrypt(plain,"v1",key);
  assertArrayEquals(plain,Sm4ExportCodec.decrypt(encrypted,key));assertNotEquals(encrypted,Sm4ExportCodec.encrypt(plain,"v1",key));
  byte[] other=new byte[16];other[0]=1;assertThrows(Exception.class,()->Sm4ExportCodec.decrypt(encrypted,other));
  assertThrows(Exception.class,()->Sm4ExportCodec.decrypt(encrypted.replace(":v1:",":v2:"),key));
  assertThrows(Exception.class,()->Sm4ExportCodec.decrypt(encrypted.substring(0,encrypted.length()-4),key));
 }
 @Test void standardSm4BlockVector()throws Exception{
  byte[] block=hex("0123456789abcdeffedcba9876543210");javax.crypto.Cipher c=javax.crypto.Cipher.getInstance("SM4/ECB/NoPadding",new org.bouncycastle.jce.provider.BouncyCastleProvider());
  c.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(block,"SM4"));assertArrayEquals(hex("681edf34d206965e86b3e94f536e4246"),c.doFinal(block));
 }
 private byte[] hex(String s){byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return out;}
 @Test void disabledByDefaultAndExplicitDecryptAuthorization()throws Exception{
  assertEquals(false,service.profile("user","db").get("enabled"));assertThrows(SecurityException.class,()->service.capture(request()));
  service.save(profile(false));String token=(String)service.capture(request()).get("envelope");assertThrows(SecurityException.class,()->service.decrypt(csv(token),"admin"));
  service.save(profile(true));String output=(String)service.decrypt(csv(token),"admin").get("csv");assertTrue(output.contains("110101199001011234"));assertFalse(output.contains(Sm4ExportCodec.PREFIX));
 }
 @Test void changedRowsHeaderAndExpiredRevokedFilesDenied()throws Exception{
  service.save(profile(true));String token=(String)service.capture(request()).get("envelope");
  assertThrows(IllegalArgumentException.class,()->service.decrypt(csv(token).replace("110101********1234","changed"),"admin"));
  assertThrows(IllegalArgumentException.class,()->service.decrypt(csv(token).replace("证件号码,备注","备注,证件号码"),"admin"));
  String id=(String)service.receipts().get(0).get("id");service.revoke(id);assertThrows(SecurityException.class,()->service.decrypt(csv(token),"admin"));
  String other=(String)service.capture(request()).get("envelope");account.put("epoch",2L);assertThrows(SecurityException.class,()->service.decrypt(csv(other),"admin"));
 }
 @Test void strictCsvQuotingAndFormulaSafety(){
  assertEquals(Arrays.asList(Arrays.asList("a","b"),Arrays.asList("x,y","z\nq")),ExportCsv.parse("a,b\r\n\"x,y\",\"z\nq\"\r\n"));
  assertThrows(IllegalArgumentException.class,()->ExportCsv.parse("\"broken"));assertThrows(IllegalArgumentException.class,()->ExportCsv.parse("\"a\"x,b"));
  assertTrue(ExportCsv.write(Collections.singletonList(Collections.singletonList("=1+1"))).contains("'=1+1"));
 }
 @Test void originalValuesNotStoredInReceiptOrAudit()throws Exception{
  service.save(profile(true));service.capture(request());
  assertFalse(new ObjectMapper().writeValueAsString(service.receipts()).contains("19900101"));
  assertFalse(jdbc.queryForList("SELECT detail FROM audit_log").toString().contains("19900101"));
 }
 @Test void expiryAndSourceGenerationChangeDenyOldFile()throws Exception{
  service.save(profile(true));String token=(String)service.capture(request()).get("envelope");
  jdbc.update("UPDATE sm4_export_receipts SET expires_at=0");assertThrows(SecurityException.class,()->service.decrypt(csv(token),"admin"));
  String fresh=(String)service.capture(request()).get("envelope");jdbc.update("UPDATE managed_objects SET generation_id='replacement'");
  assertThrows(SecurityException.class,()->service.decrypt(csv(fresh),"admin"));
 }
 @Test void rowEncryptionCombinesFieldsAndAllowsWholeRowSorting()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");
  r.put("labels",Arrays.asList("证件号码","姓名"));r.put("displayed",Arrays.asList(Arrays.asList("same","张*"),Arrays.asList("same","李*")));
  Map<String,Object> first=new LinkedHashMap<>();first.put("0","first-card");first.put("1","张三");
  Map<String,Object> second=new LinkedHashMap<>();second.put("0","second-card");second.put("1","李四");r.put("originals",Arrays.asList(first,second));
  List<?> encrypted=(List<?>)service.captureRows(r).get("envelopes");assertEquals(2,encrypted.size());
  Map<?,?> payload=new ObjectMapper().readValue(Sm4ExportCodec.decrypt((String)encrypted.get(0),key),Map.class);
  assertEquals(Collections.singletonList(first),payload.get("originals"));
  String csv="证件号码,姓名,__gateway_sm4\r\nsame,李*,"+encrypted.get(1)+"\r\nsame,张*,"+encrypted.get(0)+"\r\n";
  List<List<String>> restored=ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv"));
  assertEquals(Arrays.asList("second-card","李四"),restored.get(1));assertEquals(Arrays.asList("first-card","张三"),restored.get(2));
  assertThrows(IllegalArgumentException.class,()->service.decryptRows(csv.replace("same,李*","same,张*"),"admin"));
 }
 @Test void rowFormatAllowsAmbiguousMaskedDuplicatesAndRejectsLegacy()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");r.put("displayed",Arrays.asList(Arrays.asList("x","y"),Arrays.asList("x","y")));
  r.put("originals",Arrays.asList(Collections.singletonMap("0","first"),Collections.singletonMap("0","second")));
  List<?> encrypted=(List<?>)service.captureRows(r).get("envelopes");
  String csv="证件号码,备注,__gateway_sm4\r\nx,y,"+encrypted.get(1)+"\r\n";
  assertEquals("second",ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv")).get(1).get(0));
  assertThrows(IllegalArgumentException.class,()->service.decryptRows(csv((String)service.capture(request()).get("envelope")),"admin"));
 }
 @Test void rowFormatEmptyResultKeepsOnlyBusinessHeader()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");r.put("displayed",Collections.emptyList());r.put("originals",Collections.emptyList());
  assertTrue(((List<?>)service.captureRows(r).get("envelopes")).isEmpty());
  assertEquals(1,ExportCsv.parse((String)service.decryptRows("证件号码,备注,__gateway_sm4\r\n","admin").get("csv")).size());
 }
 @Test void csvLimitsAlsoApplyWithoutFinalNewline(){
  assertThrows(IllegalArgumentException.class,()->ExportCsv.parse(String.join(",",Collections.nCopies(257,"x"))));
  assertThrows(IllegalArgumentException.class,()->ExportCsv.parse(String.join("\n",Collections.nCopies(1003,"x"))));
  assertThrows(IllegalArgumentException.class,()->ExportCsv.parse(String.join("",Collections.nCopies(400000,"中"))));
 }
 @Test void ambiguousMaskedDuplicatesAreRejectedBeforeCapture()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();
  r.put("displayed",Arrays.asList(Arrays.asList("same","same"),Arrays.asList("same","same")));
  r.put("originals",Arrays.asList(Collections.singletonMap("0","first"),Collections.singletonMap("0","second")));
  assertThrows(IllegalArgumentException.class,()->service.capture(r));assertTrue(service.receipts().isEmpty());
 }
 @Test void columnTypesAreRequiredAndAuthenticated()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.remove("types");
  assertThrows(IllegalArgumentException.class,()->service.capture(r));
  final Map<String,Object> invalid=request();invalid.put("types",Arrays.asList(java.sql.Types.INTEGER,java.sql.Types.VARCHAR));
  assertThrows(IllegalArgumentException.class,()->service.capture(invalid));
  String envelope=(String)service.capture(request()).get("envelope");
  Map<?,?> payload=new ObjectMapper().readValue(Sm4ExportCodec.decrypt(envelope,key),Map.class);
  assertEquals(request().get("types"),payload.get("types"));
 }
 @Test void logicalAliasUsesPhysicalDatabaseForPermissionCheck()throws Exception{
  jdbc.update("UPDATE logical_databases SET logical_name='alias' WHERE logical_name='PUBLIC'");
  service.save(profile(true));Map<String,Object> r=request();
  Map<String,String> source=new LinkedHashMap<>();source.put("database","alias");source.put("table","src");source.put("column","secret");r.put("sources",Collections.singletonList(source));
  String envelope=(String)service.capture(r).get("envelope");
  assertTrue(((String)service.decrypt(csv(envelope),"admin").get("csv")).contains("110101199001011234"));
 }
 @Test void oversizedRowsAndCiphertextNeverProduceReceipt()throws Exception{
  service.save(profile(true));Map<String,Object> tooMany=request();
  tooMany.put("displayed",Collections.nCopies(3,Arrays.asList("x","y")));
  tooMany.put("originals",Collections.nCopies(3,Collections.singletonMap("0","secret")));
  assertThrows(IllegalArgumentException.class,()->service.capture(tooMany));
  Map<String,Object> tooLong=request();tooLong.put("originals",Collections.singletonList(Collections.singletonMap("0",String.join("",Collections.nCopies(30000,"x")))));
  assertThrows(IllegalArgumentException.class,()->service.capture(tooLong));assertTrue(service.receipts().isEmpty());
 }
 @Test void currentDatabasePermissionsAreCheckedAgain()throws Exception{
  service.save(profile(true));String envelope=(String)service.capture(request()).get("envelope");
  jdbc.execute("CREATE USER limited PASSWORD 'test-password'");
  account.put("username","limited");account.put("password","test-password");
  assertThrows(java.sql.SQLException.class,()->service.decrypt(csv(envelope),"admin"));
 }
 @Test void identicalDuplicatesWithSameOriginalRemainSupported()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();
  r.put("displayed",Collections.nCopies(2,Arrays.asList("x","y")));
  r.put("originals",Collections.nCopies(2,Collections.singletonMap("0","secret")));
  String envelope=(String)service.capture(r).get("envelope");
  String output=(String)service.decrypt("证件号码,备注\r\nx,y\r\nx,y\r\n"+envelope+",\r\n","admin").get("csv");
  assertEquals(3,ExportCsv.parse(output).size());
 }
 @Test void batchChecksEachTableAndAccountOnceAndNeverCachesAcrossQueries()throws Exception{
  Map<String,Object> policy=profile(true);policy.put("maxRows",200);service.save(policy);
  Map<String,Object> r=request();r.put("format","ROW_V2");
  r.put("displayed",Collections.nCopies(180,Arrays.asList("masked","remark")));
  r.put("originals",Collections.nCopies(180,Collections.singletonMap("0","original")));
  Map<String,String> second=new LinkedHashMap<>(((List<Map<String,String>>)r.get("sources")).get(0));second.put("column","other");
  r.put("sources",Arrays.asList(((List<?>)r.get("sources")).get(0),second));
  ObjectIdentityProvider identities=(ObjectIdentityProvider)ReflectionTestUtils.getField(service,"identities");
  clearInvocations(access,identities);
  assertEquals(180,((List<?>)service.captureRows(r).get("envelopes")).size());
  verify(access,times(1)).resolve("user","db");verify(identities,times(1)).identity(1,"PUBLIC","src");
  assertEquals(180,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
  service.captureRows(r);verify(identities,times(2)).identity(1,"PUBLIC","src");
  r.put("displayed",Collections.nCopies(201,Arrays.asList("masked","remark")));
  r.put("originals",Collections.nCopies(201,Collections.singletonMap("0","original")));
  assertThrows(IllegalArgumentException.class,()->service.captureRows(r));
  assertEquals(360,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
 }
 @Test void fiveDifferentUsersCanCapture180RowsSimultaneouslyWithoutMixingIdentities()throws Exception{
  java.util.concurrent.CountDownLatch arrived=new java.util.concurrent.CountDownLatch(5);
  // Complete Mockito stubbing before concurrent invocations; Mockito does not support concurrent stubbing.
  java.util.concurrent.CountDownLatch configured=new java.util.concurrent.CountDownLatch(1);
  ObjectIdentityProvider identities=(ObjectIdentityProvider)ReflectionTestUtils.getField(service,"identities");
  when(identities.identity(1,"PUBLIC","src")).thenAnswer(call->{arrived.countDown();assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS),"five requests must enter together");return "0:1:2";});
  java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newFixedThreadPool(5);
  List<java.util.concurrent.Future<?>> results=new ArrayList<>();long start=System.nanoTime();
  try{
   for(int u=0;u<5;u++){
    final String username="user"+u;Map<String,Object> identity=new LinkedHashMap<>(account);identity.put("epoch",100L+u);
    when(access.resolve(username,"db")).thenReturn(identity);
    Map<String,Object> policy=profile(true);policy.put("username",username);policy.put("maxRows",200);service.save(policy);
    results.add(executor.submit(()->{
     try{
      assertTrue(configured.await(10,java.util.concurrent.TimeUnit.SECONDS));
      Map<String,Object> r=request();r.put("username",username);r.put("format","ROW_V2");
      r.put("displayed",Collections.nCopies(180,Arrays.asList("masked","remark")));
      r.put("originals",Collections.nCopies(180,Collections.singletonMap("0",username+"-secret")));
      org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
      List<?> envelopes=(List<?>)tx.execute(status->{
       try{return service.captureRows(r);}catch(Exception e){throw new RuntimeException(e);}
      }).get("envelopes");assertEquals(180,envelopes.size());
      for(Object envelope:envelopes){
       Map<?,?> payload=new ObjectMapper().readValue(Sm4ExportCodec.decrypt((String)envelope,key),Map.class);
       assertEquals(username,payload.get("username"));assertEquals(identity.get("epoch").toString(),payload.get("epoch").toString());
       assertEquals(Collections.singletonList(Collections.singletonMap("0",username+"-secret")),payload.get("originals"));
      }
     }catch(Exception e){throw new RuntimeException(e);}
    }));
   }
   configured.countDown();
   for(java.util.concurrent.Future<?> result:results)result.get(30,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals(900,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
   verify(identities,times(5)).identity(1,"PUBLIC","src");
   System.out.println("SM4 five-user service test: 900 encrypted/decrypted rows, elapsed ms="+(System.nanoTime()-start)/1000000);
  }finally{configured.countDown();executor.shutdownNow();}
 }
 @Test void failedBatchWritesNoPartialReceiptsAndReleasesSlot()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");
  r.put("displayed",Arrays.asList(Arrays.asList("x","y"),Arrays.asList("a","b")));
  r.put("originals",Arrays.asList(Collections.singletonMap("0","ok"),Collections.singletonMap("0",String.join("",Collections.nCopies(30000,"x")))));
  assertThrows(IllegalArgumentException.class,()->service.captureRows(r));assertTrue(service.receipts().isEmpty());
  r.put("originals",Arrays.asList(Collections.singletonMap("0","ok"),Collections.singletonMap("0","ok2")));
  assertEquals(2,((List<?>)service.captureRows(r).get("envelopes")).size());
 }
 @Test void auditFailureRollsBackBatchedReceipts()throws Exception{
  service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");
  jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_capture CHECK(action<>'SM4_EXPORT_CAPTURED')");
  org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
  assertThrows(RuntimeException.class,()->tx.execute(status->{
   try{return service.captureRows(r);}catch(Exception e){throw new RuntimeException(e);}
  }));
  assertTrue(service.receipts().isEmpty());
  jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT reject_capture");
  assertEquals(1,((List<?>)service.captureRows(r).get("envelopes")).size());
 }

 Map<String,Object> editableRequest()throws Exception{
  Map<String,Object> policy=profile(true);policy.put("rowKeyColumn","记录ID");policy.put("maxRows",200);service.save(policy);
  Map<String,Object> r=request();r.put("format","ROW_V2");r.put("labels",Arrays.asList("记录ID","证件号码","备注"));
  r.put("types",Arrays.asList(java.sql.Types.VARCHAR,java.sql.Types.VARCHAR,java.sql.Types.VARCHAR));
  r.put("displayed",Arrays.asList(Arrays.asList("1","same-mask","old"),Arrays.asList("2","same-mask","old")));
  r.put("originals",Arrays.asList(Collections.singletonMap("1","first-card"),Collections.singletonMap("1","second-card")));return r;
 }
 Sm4ExportService transactionalService(){
  org.springframework.aop.framework.ProxyFactory factory=new org.springframework.aop.framework.ProxyFactory(service);
  factory.setProxyTargetClass(true);
  factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),
    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
  return (Sm4ExportService)factory.getProxy();
 }
 @Test void liveObservationTracksEditableCryptoAndNeverStoresBusinessValues()throws Exception{
  CryptoObservations monitor=new CryptoObservations();monitor.start();
  try{
   Sm4ExportController controller=new Sm4ExportController(transactionalService(),access);
   Map<String,Object> r=editableRequest();org.springframework.mock.web.MockHttpServletRequest http=new org.springframework.mock.web.MockHttpServletRequest();
   String requestId=UUID.randomUUID().toString();http.addHeader("X-Gateway-Observation-Request-Id",requestId);
   Map<?,?> result=(Map<?,?>)controller.capture("test-token",r,http);
   List<?> tokens=(List<?>)result.get("envelopes");
   assertEquals(1,monitor.detail(requestId).size());
   CryptoObservations.Event event=monitor.detail(requestId).get(0);
   assertEquals(com.example.doris.gateway.observability.Observation.Reason.OK,event.reason);
   assertEquals(3,event.stages.get(com.example.doris.gateway.observability.Observation.Stage.SM4_ENCRYPT).calls);
   String detail=new ObjectMapper().writeValueAsString(event);assertFalse(detail.contains("first-card"));assertFalse(detail.contains("证件号码"));assertFalse(detail.contains(tokens.get(0).toString()));
   Map<String,String> body=Collections.singletonMap("csv","记录ID,证件号码,备注,__gateway_sm4\r\n1,same-mask,new,"+tokens.get(0)+"\r\n");
   controller.decrypt(body,()->"admin");
   assertEquals(1L,monitor.summary("worker",com.example.doris.gateway.observability.Observation.Operation.DECRYPT_FILE,15).get("count"));
   assertEquals("",com.example.doris.gateway.observability.Telemetry.current().requestId());
  }finally{monitor.stop();}
 }
 @Test void observationDoesNotReportSuccessWhenReceiptTransactionRollsBack()throws Exception{
  CryptoObservations monitor=new CryptoObservations();monitor.start();
  try{
   service.save(profile(true));Map<String,Object> r=request();r.put("format","ROW_V2");
   jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT observation_reject CHECK(action<>'SM4_EXPORT_CAPTURED')");
   Sm4ExportController controller=new Sm4ExportController(transactionalService(),access);
   org.springframework.mock.web.MockHttpServletRequest http=new org.springframework.mock.web.MockHttpServletRequest();String requestId=UUID.randomUUID().toString();http.addHeader("X-Gateway-Observation-Request-Id",requestId);
   assertThrows(Exception.class,()->controller.capture("test-token",r,http));
   assertTrue(service.receipts().isEmpty());assertNotEquals(com.example.doris.gateway.observability.Observation.Reason.OK,monitor.detail(requestId).get(0).reason);
   assertEquals("",com.example.doris.gateway.observability.Telemetry.current().requestId());
  }finally{monitor.stop();}
 }
 @Test void editableCsvCanAddRemoveRenameAndReorderOrdinaryColumns()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(editableRequest()).get("envelopes");
  String csv="新增备注,证件号码,记录ID,__gateway_sm4\r\nchanged,same-mask,2,"+tokens.get(1)+"\r\nother,same-mask,1,"+tokens.get(0)+"\r\n";
  List<List<String>> out=ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv"));
  assertEquals(Arrays.asList("新增备注","证件号码","记录ID"),out.get(0));
  assertEquals(Arrays.asList("changed","second-card","2"),out.get(1));
  assertEquals(Arrays.asList("other","first-card","1"),out.get(2));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_batches",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SM4_EXPORT_CAPTURED'",Integer.class));
 }
 @Test void editableCsvRejectsProtectedChangesMissingColumnsSwappedCipherAndDuplicates()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(editableRequest()).get("envelopes");
  String csv="记录ID,证件号码,备注,__gateway_sm4\r\n1,same-mask,new,"+tokens.get(0)+"\r\n";
  for(String invalid:Arrays.asList(csv.replace("same-mask","changed"),csv.replace("证件号码","renamed"),
       csv.replace("1,same-mask","2,same-mask"),csv.replace(tokens.get(0).toString(),tokens.get(1).toString()),
       csv.replace("备注","证件号码"),csv+"1,same-mask,new,"+tokens.get(0)+"\r\n",
       csv.substring(0,csv.length()-6)+"xxxx\r\n")){
   assertThrows(Exception.class,()->service.decryptRows(invalid,"admin"));
  }
  assertEquals(2,ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv")).size());
 }
 @Test void editableCaptureRequiresUniqueUnprotectedAnchor()throws Exception{
  Map<String,Object> r=editableRequest();
  r.put("displayed",Arrays.asList(Arrays.asList("1","same-mask","x"),Arrays.asList("1","same-mask","y")));
  assertThrows(IllegalArgumentException.class,()->service.captureRows(r));
  r.put("displayed",Arrays.asList(Arrays.asList("","same-mask","x"),Arrays.asList("2","same-mask","y")));
  assertThrows(IllegalArgumentException.class,()->service.captureRows(r));
  r.put("displayed",Arrays.asList(Arrays.asList("1","same-mask","x"),Arrays.asList("2","same-mask","y")));
  r.put("originals",Arrays.asList(Collections.singletonMap("0","raw1"),Collections.singletonMap("0","raw2")));
  assertThrows(IllegalArgumentException.class,()->service.captureRows(r));assertTrue(service.receipts().isEmpty());
 }
 @Test void compactRowsAreShorterAndBatchMetadataContainsNoOriginalValues()throws Exception{
  Map<String,Object> r=editableRequest();List<?> compact=(List<?>)service.captureRows(r).get("envelopes");
  Map<String,Object> policy=profile(true);policy.put("rowKeyColumn","");service.save(policy);
  List<?> legacy=(List<?>)service.captureRows(r).get("envelopes");
  assertTrue(compact.get(0).toString().length()<legacy.get(0).toString().length());
  System.out.println("ROW_V3 sample chars="+compact.get(0).toString().length()+", ROW_V2="+legacy.get(0).toString().length());
  String stored=new ObjectMapper().writeValueAsString(jdbc.queryForList("SELECT * FROM sm4_export_batches"));
  assertFalse(stored.contains("first-card"));assertFalse(stored.contains(compact.get(0).toString()));
  String savedMetadata=jdbc.queryForObject("SELECT metadata FROM sm4_export_batches",String.class);
  String metadataPlain=new String(Sm4ExportCodec.decrypt(savedMetadata,key),java.nio.charset.StandardCharsets.UTF_8);
  assertFalse(metadataPlain.contains("first-card"));assertFalse(metadataPlain.contains("second-card"));
  assertFalse(metadataPlain.contains("same-mask"));assertFalse(metadataPlain.contains("\"originals\""));
  String legacyCsv="记录ID,证件号码,备注,__gateway_sm4\r\n1,same-mask,old,"+legacy.get(0)+"\r\n";
  assertTrue(service.decryptRows(legacyCsv,"admin").get("csv").toString().contains("first-card"));
  assertThrows(Exception.class,()->service.decryptRows(legacyCsv.replace(",old,",",edited,"),"admin"));
 }
 @Test void batchExpiryRevocationAndAccountChangesStillDenyDecryption()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(editableRequest()).get("envelopes");
  String csv="记录ID,证件号码,__gateway_sm4\r\n1,same-mask,"+tokens.get(0)+"\r\n";
  account.put("epoch",2L);assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));account.put("epoch",1L);
  service.revoke((String)service.receipts().get(0).get("id"));assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
  jdbc.update("UPDATE sm4_export_receipts SET revoked=FALSE, expires_at=0");assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
  service.cleanupExpiredExports();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_batches",Integer.class));
  assertTrue(service.receipts().isEmpty());assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
 }
 @Test void editableRejectsMixedBatchesAndCurrentPermissionOrSourceChanges()throws Exception{
  Map<String,Object> request=editableRequest();
  List<?> first=(List<?>)service.captureRows(request).get("envelopes"),second=(List<?>)service.captureRows(request).get("envelopes");
  String csv="记录ID,证件号码,__gateway_sm4\r\n1,same-mask,"+first.get(0)+"\r\n";
  assertThrows(IllegalArgumentException.class,()->service.decryptRows(csv+"2,same-mask,"+second.get(1)+"\r\n","admin"));
  service.save(profile(false));assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
  service.save(profile(true));
  jdbc.execute("CREATE USER editable_limited PASSWORD 'test-password'");
  account.put("username","editable_limited");account.put("password","test-password");
  assertThrows(java.sql.SQLException.class,()->service.decryptRows(csv,"admin"));
  account.put("username","sa");account.put("password","");
  jdbc.update("UPDATE managed_objects SET generation_id='replacement'");
  assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
 }
 @Test void cleanupKeepsUnexpiredBatchesKeysAndConfigurationAudit()throws Exception{
  Map<String,Object> request=editableRequest();service.captureRows(request);
  String expired=(String)service.receipts().get(0).get("id");service.captureRows(request);
  jdbc.update("UPDATE sm4_export_receipts SET expires_at=0 WHERE id=?",expired);
  jdbc.update("INSERT INTO audit_log(action,detail,created_at) VALUES('SM4_EXPORT_DECRYPTED','old','2000-01-01T00:00:00Z')");
  jdbc.update("INSERT INTO audit_log(action,detail,created_at) VALUES('SM4_EXPORT_PROFILE_CHANGED','old-config','2000-01-01T00:00:00Z')");
  byte[] before=Files.readAllBytes(dir.resolve("keys"));service.cleanupExpiredExports();
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE detail='old'",Integer.class));
  ReflectionTestUtils.setField(service,"auditRetentionDays",90);service.cleanupExpiredExports();
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_batches",Integer.class));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE detail='old'",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE detail='old-config'",Integer.class));
  assertArrayEquals(before,Files.readAllBytes(dir.resolve("keys")));
 }
 @Test void editableMetadataFailureRollsBackEntireCapture()throws Exception{
  Map<String,Object> request=editableRequest();
  jdbc.execute("ALTER TABLE sm4_export_batches ADD CONSTRAINT reject_batch CHECK(LENGTH(metadata)<1)");
  org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
  assertThrows(RuntimeException.class,()->tx.execute(status->{
   try{return service.captureRows(request);}catch(Exception e){throw new RuntimeException(e);}
  }));
  assertTrue(service.receipts().isEmpty());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SM4_EXPORT_CAPTURED'",Integer.class));
 }
 @Test void fiveEditableUsersKeepOnlyFiveBatchRecordsFor900Rows()throws Exception{
  Map<String,Object> template=editableRequest();
  java.util.concurrent.CountDownLatch arrived=new java.util.concurrent.CountDownLatch(5);
  ObjectIdentityProvider identities=(ObjectIdentityProvider)ReflectionTestUtils.getField(service,"identities");
  when(identities.identity(1,"PUBLIC","src")).thenAnswer(call->{arrived.countDown();assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS));return "0:1:2";});
  java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(5);
  List<java.util.concurrent.Future<?>> pending=new ArrayList<>();
  try{
   for(int u=0;u<5;u++){
    final String user="editable"+u;when(access.resolve(user,"db")).thenReturn(new LinkedHashMap<>(account));
    Map<String,Object> policy=profile(true);policy.put("username",user);policy.put("maxRows",200);policy.put("allowEdit",true);service.save(policy);
    pending.add(pool.submit(()->{
     try{
      Map<String,Object> r=new LinkedHashMap<>(template);r.put("username",user);
      List<List<String>> views=new ArrayList<>();List<Map<String,Object>> raw=new ArrayList<>();
      for(int n=0;n<180;n++){views.add(Arrays.asList(String.valueOf(n),"mask","note"));raw.add(Collections.singletonMap("1",user+"-raw-"+n));}
      r.put("displayed",views);r.put("originals",raw);
      org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
      List<?> tokens=(List<?>)tx.execute(status->{try{return service.captureRows(r);}catch(Exception e){throw new RuntimeException(e);}}).get("envelopes");
      assertEquals(180,tokens.size());
      for(int n=0;n<tokens.size();n++){
       Map<?,?> packet=new ObjectMapper().readValue(Sm4ExportCodec.decrypt(tokens.get(n).toString(),key),Map.class);
       assertEquals(4,packet.get("v"));assertEquals(Collections.singletonList(user+"-raw-"+n),packet.get("o"));
       String metadata=jdbc.queryForObject("SELECT metadata FROM sm4_export_batches WHERE id=?",String.class,packet.get("b"));
       Map<?,?> common=new ObjectMapper().readValue(Sm4ExportCodec.decrypt(metadata,key),Map.class);
       assertEquals(user,common.get("username"));
      }
     }catch(Exception e){throw new RuntimeException(e);}
    }));
   }
   for(java.util.concurrent.Future<?> result:pending)result.get(30,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts",Integer.class));
   assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_batches",Integer.class));
   assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SM4_EXPORT_CAPTURED'",Integer.class));
   verify(identities,times(5)).identity(1,"PUBLIC","src");
  }finally{pool.shutdownNow();}
 }
 Map<String,Object> automaticRequest()throws Exception{
  Map<String,Object> p=profile(true);p.put("allowEdit",true);p.put("ttlDays",365);service.save(p);
  Map<String,Object> r=request();r.put("format","ROW_V2");
  r.put("labels",Arrays.asList("证件号码","备注"));r.put("displayed",Arrays.asList(Arrays.asList("mask-A","old"),Arrays.asList("mask-B","old")));
  r.put("originals",Arrays.asList(Collections.singletonMap("0","raw-A"),Collections.singletonMap("0","raw-B")));return r;
 }
 @Test void automaticRowsNeedNoBusinessIdentifierAndAllowEveryOrdinaryColumnEdit()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(automaticRequest()).get("envelopes");
  Map<?,?> packet=new ObjectMapper().readValue(Sm4ExportCodec.decrypt(tokens.get(0).toString(),key),Map.class);assertEquals(4,packet.get("v"));
  String csv="新增,证件号码,__gateway_sm4\r\nchanged,mask-B,"+tokens.get(1)+"\r\nother,mask-A,"+tokens.get(0)+"\r\n";
  List<List<String>> out=ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv"));
  assertEquals(Arrays.asList("新增","证件号码"),out.get(0));assertEquals(Arrays.asList("changed","raw-B"),out.get(1));
  assertEquals(Arrays.asList("other","raw-A"),out.get(2));
  String subset="证件号码,__gateway_sm4\r\nmask-A,"+tokens.get(0)+"\r\n";
  assertEquals(Arrays.asList("raw-A"),ExportCsv.parse((String)service.decryptRows(subset,"admin").get("csv")).get(1));
  String meta=jdbc.queryForObject("SELECT metadata FROM sm4_export_batches WHERE id=?",String.class,packet.get("b"));
  String plain=new String(Sm4ExportCodec.decrypt(meta,key),java.nio.charset.StandardCharsets.UTF_8);
  assertFalse(plain.contains("raw-A"));assertFalse(plain.contains("mask-A"));
 }
 @Test void automaticRowsRejectTamperMissingProtectedColumnsMixedBatchAndDuplicateRows()throws Exception{
  Map<String,Object> r=automaticRequest();List<?> tokens=(List<?>)service.captureRows(r).get("envelopes");
  List<?> other=(List<?>)service.captureRows(r).get("envelopes");
  String line="mask-A,x,"+tokens.get(0)+"\r\n",header="证件号码,备注,__gateway_sm4\r\n",csv=header+line;
  for(String bad:Arrays.asList(csv.replace("mask-A","changed"),csv.replace("证件号码","renamed"),
      "备注,__gateway_sm4\r\nx,"+tokens.get(0)+"\r\n",csv.replace(tokens.get(0).toString(),tokens.get(1).toString()),
      csv+line,csv+"mask-B,x,"+other.get(1)+"\r\n",csv.replace("备注","证件号码"),
      csv.replace(tokens.get(0).toString(),tokens.get(0).toString().substring(0,40)))){
   assertThrows(Exception.class,()->service.decryptRows(bad,"admin"));
  }
  String id=(String)service.receipts().get(0).get("id");jdbc.update("UPDATE sm4_export_receipts SET revoked=TRUE");
  assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
 }
 @Test void identicalProtectedViewsCanBeSwappedAndAreNotBusinessIdentityProof()throws Exception{
  Map<String,Object> r=automaticRequest();r.put("displayed",Arrays.asList(Arrays.asList("same",""),Arrays.asList("same","")));
  List<?> tokens=(List<?>)service.captureRows(r).get("envelopes");
  String csv="证件号码,__gateway_sm4\r\nsame,"+tokens.get(1)+"\r\nsame,"+tokens.get(0)+"\r\n";
  List<List<String>> out=ExportCsv.parse((String)service.decryptRows(csv,"admin").get("csv"));
  assertEquals(Collections.singletonList("raw-B"),out.get(1));assertEquals(Collections.singletonList("raw-A"),out.get(2));
 }
 @Test void stopCaptureDoesNotRevokeDecryptButDeleteAndGrantRemovalDo()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(automaticRequest()).get("envelopes");String csv="证件号码,__gateway_sm4\r\nmask-A,"+tokens.get(0)+"\r\n";
  Map<String,Object> p=profile(true);p.put("enabled",false);p.put("allowEdit",false);service.save(p);
  assertTrue(service.decryptRows(csv,"admin").get("csv").toString().contains("raw-A"));
  Map<String,Object> disabled=request();disabled.put("format","ROW_V2");assertThrows(SecurityException.class,()->service.captureRows(disabled));
  p.put("allowDecrypt",false);service.save(p);assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));
  p.put("allowDecrypt",true);service.save(p);service.deleteProfile(p);assertTrue(service.profiles().isEmpty());
  assertThrows(SecurityException.class,()->service.decryptRows(csv,"admin"));service.save(p);
  assertTrue(service.decryptRows(csv,"admin").get("csv").toString().contains("raw-A"));
 }
 @Test void daysBoundsLegacyMinutesAndExistingExpiryArePreserved()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(automaticRequest()).get("envelopes");
  long expires=((Number)service.receipts().get(0).get("expiresAt")).longValue();
  assertTrue(expires-System.currentTimeMillis()>364L*86400000);assertEquals(525600,service.profile("user","db").get("ttlMinutes"));
  Map<String,Object> p=profile(true);p.put("ttlDays",1);service.save(p);assertEquals(expires,((Number)service.receipts().get(0).get("expiresAt")).longValue());
  for(Object bad:Arrays.asList(0,366,1.5)){p.put("ttlDays",bad);assertThrows(IllegalArgumentException.class,()->service.save(p));}
  p.remove("ttlDays");p.put("ttlMinutes",60);service.save(p);p.remove("ttlMinutes");service.save(p);assertEquals(60,service.profile("user","db").get("ttlMinutes"));
 }
 @Test void upgradeRemovesLegacyAnchorRequirementWithoutReinterpretingOldFiles()throws Exception{
  List<?> tokens=(List<?>)service.captureRows(editableRequest()).get("envelopes");service.initialize();
  assertEquals(true,service.profile("user","db").get("allowEdit"));assertEquals("",service.profile("user","db").get("rowKeyColumn"));
  String legacy="记录ID,证件号码,__gateway_sm4\r\n1,same-mask,"+tokens.get(0)+"\r\n";
  assertTrue(service.decryptRows(legacy,"admin").get("csv").toString().contains("first-card"));
  assertThrows(IllegalArgumentException.class,()->service.decryptRows(legacy.replace("记录ID,","").replace("1,same-mask","same-mask"),"admin"));
  Map<String,Object> r=request();r.put("format","ROW_V2");List<?> fresh=(List<?>)service.captureRows(r).get("envelopes");
  assertEquals(4,new ObjectMapper().readValue(Sm4ExportCodec.decrypt(fresh.get(0).toString(),key),Map.class).get("v"));
 }
 @Test void autoAssociationTracksBindingsPreservesManualOverridesAndDeletion()throws Exception{
  AccountAccessStore bindings=new AccountAccessStore(jdbc);bindings.initialize();
  jdbc.update("INSERT INTO connections(id,name,host,port,username,password_enc,created_at) VALUES(1,'test','localhost',9030,'user','unused','now')");
  jdbc.update("UPDATE logical_databases SET logical_name='db' WHERE logical_name='PUBLIC'");bindings.bind(1,"db","ACTIVE");
  Map<String,Object> defaults=profile(true);defaults.put("linked",true);defaults.put("allowEdit",true);defaults.put("ttlDays",10);service.saveDefaults(defaults);
  assertEquals(true,service.profile("user","db").get("enabled"));assertEquals(1,service.profiles().size());
  Map<String,Object> manual=profile(false);manual.put("enabled",false);service.save(manual);
  defaults.put("ttlDays",20);service.saveDefaults(defaults);assertEquals(false,service.profile("user","db").get("allowDecrypt"));
  service.deleteProfile(manual);assertTrue(service.profiles().isEmpty());assertEquals(false,service.profile("user","db").get("enabled"));
  service.saveDefaults(defaults);assertTrue(service.profiles().isEmpty());
  jdbc.update("INSERT INTO logical_databases(connection_id,logical_name,physical_database,storage_unit,status,updated_at) VALUES(1,'next','PUBLIC','unit','ACTIVE','now')");bindings.bind(1,"next","ACTIVE");
  assertEquals(true,service.profile("user","next").get("enabled"));assertEquals(1,service.profiles().size());
  bindings.bind(1,"next","REMOVED");assertEquals(false,service.profile("user","next").get("enabled"));
  bindings.bind(1,"next","ACTIVE");defaults.put("linked",false);service.saveDefaults(defaults);assertEquals(false,service.profile("user","next").get("enabled"));
  defaults.put("linked",true);service.saveDefaults(defaults);bindings.lifecycle("user","DISABLED",false);assertEquals(false,service.profile("user","next").get("enabled"));
 }
 @Test void multiDatabaseSaveIsAtomicAndRejectsUnknownAccountBindings()throws Exception{
  Map<String,Object> p=profile(true);p.put("databaseNames",Arrays.asList("db","bad"));
  when(access.resolve("user","bad")).thenThrow(new SecurityException("not bound"));
  assertThrows(SecurityException.class,()->transactionalService().saveBatch(p));assertTrue(service.profiles().isEmpty());
  when(access.resolve("user","next")).thenReturn(account);p.put("databaseNames",Arrays.asList("db","next"));transactionalService().saveBatch(p);assertEquals(2,service.profiles().size());
 }

 @Test void httpProfileCaptureEditedCsvDownloadAndDeletionRoundTrip()throws Exception{
  org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Sm4ExportController(transactionalService(),access)).build();
  ObjectMapper json=new ObjectMapper();Map<String,Object> p=profile(true);p.put("allowEdit",true);p.put("ttlDays",365);p.put("databaseNames",Arrays.asList("db"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sm4-export/profiles/batch").contentType("application/json").content(json.writeValueAsBytes(p))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  Map<String,Object> r=request();r.put("format","ROW_V2");
  String response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/sql/export-capture").header("X-Gateway-Lineage-Token","test").contentType("application/json").content(json.writeValueAsBytes(r))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
  String token=((List<?>)json.readValue(response,Map.class).get("envelopes")).get(0).toString();
  String csv="新增,证件号码,__gateway_sm4\r\n新备注,110101********1234,"+token+"\r\n";
  String output=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sm4-export/decrypt").principal(()->"admin").contentType("application/json").content(json.writeValueAsBytes(Collections.singletonMap("csv",csv)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(output.contains("新备注"));assertTrue(output.contains("110101199001011234"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/sm4-export/profiles").contentType("application/json").content(json.writeValueAsBytes(p))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sm4-export/decrypt").principal(()->"admin").contentType("application/json").content(json.writeValueAsBytes(Collections.singletonMap("csv",csv)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
 }

}
