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
}
