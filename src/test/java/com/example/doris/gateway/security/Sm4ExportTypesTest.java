package com.example.doris.gateway.security;
import com.example.doris.gateway.export.ExportValue;
import com.example.doris.gateway.observability.Telemetry;
import com.example.doris.gateway.observability.Observation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryHeader;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Sm4ExportTypesTest {
 private final ObjectMapper json=new ObjectMapper();
 private QueryHeader header(String name,int type){return new QueryHeader("","",name,name,type,"test-type-"+type,128,6,false,false,false,false);}
 private Sm4ExportRows.RowReader reader(Object[][] values){return new Sm4ExportRows.RowReader(){int row=-1;
  public boolean next(){return ++row<values.length;}
  public Object value(int c){return c==1?"masked":values[row][c-1];}
  public Object original(int c){return "original-secret";}
  public boolean protectedColumn(int c){return c==1;}
 };}
 private Map<String,Object> policy(){Map<String,Object> p=new HashMap<>();p.put("allowEdit",true);p.put("maxRows",200);return p;}
 @Test void ordinaryTemporalFloatingBooleanBinaryAndComplexTextKeepValuesAndMetadata()throws Exception{
  Timestamp stamp=Timestamp.valueOf("2026-10-21 12:34:56.123456");byte[] binary={0,1,-1};
  Object[] values={"secret",java.sql.Date.valueOf("2026-10-21"),stamp,LocalDateTime.of(2026,10,21,12,0,1,123456000),1.2345678901234567d,1.25f,true,binary,"{\"x\":[1,null,\"中文\"]}",new BigInteger("170141183460469231731687303715884105727"),new BigDecimal("12345678901234567890.123456789"),null};
  int[] types={Types.VARCHAR,Types.DATE,Types.TIMESTAMP,Types.TIMESTAMP,Types.DOUBLE,Types.FLOAT,Types.BOOLEAN,Types.VARBINARY,Types.OTHER,Types.NUMERIC,Types.DECIMAL,Types.DATE};
  List<QueryHeader> headers=new ArrayList<>();for(int i=0;i<types.length;i++)headers.add(header("c"+i,types[i]));List<QueryHeader> before=new ArrayList<>(headers);
  Map<String,Object>[] captured=new Map[1];
  Sm4ExportRows result=Sm4ExportRows.collect(reader(new Object[][]{values}),headers,"user","db",policy(),Collections.emptyList(),Telemetry.begin(Observation.Operation.ENCRYPT_QUERY,null,null),body->{captured[0]=json.readValue(body,Map.class);assertFalse(body.contains("12345678901234567890"));assertFalse(body.contains("2026-10-21"));assertFalse(body.contains("中文"));return "{\"envelopes\":[\"__GW_SM4_V1__:synthetic\"]}";});
  assertTrue(result.next());assertEquals("masked",result.value(0));
  for(int i=1;i<values.length;i++){assertSame(before.get(i),headers.get(i));if(values[i] instanceof byte[])assertArrayEquals((byte[])values[i],(byte[])result.value(i));else assertEquals(values[i],result.value(i));}
  assertNotSame(stamp,result.value(2));assertNotSame(binary,result.value(7));assertFalse(captured[0].containsKey("displayed"));assertEquals("ROW_TYPED_V1",captured[0].get("format"));
 }
 @Test void everyScalarHasAnExplicitLosslessEncoding(){
  Object[] values={null,"a,\n\"中文",new BigInteger("170141183460469231731687303715884105727"),new BigDecimal("123.450000"),Float.MIN_VALUE,Double.MAX_VALUE,Double.NaN,Double.NEGATIVE_INFINITY,true,LocalDate.of(0,1,1),LocalTime.of(1,2,3,123456789),LocalDateTime.of(2026,10,21,1,2,3,123456789),OffsetDateTime.parse("2026-10-21T12:00:00+08:00"),Instant.parse("2026-10-21T04:00:00Z"),Duration.ofHours(-26),new byte[]{0,-1}};
  for(Object v:values){List<String> encoded=ExportValue.encode(v);assertEquals(v==null?"":encoded.get(1),ExportValue.decode(encoded));}
  assertEquals("123.450000",ExportValue.text(values[3]));assertEquals("AP8=",ExportValue.text(new byte[]{0,-1}));
  assertThrows(IllegalArgumentException.class,()->ExportValue.encode(new Object()));
  assertThrows(IllegalArgumentException.class,()->ExportValue.decode(Arrays.asList("integer","secret-value")));
 }
 @Test void bufferBudgetAndUnsupportedDriverObjectsFailBeforeCapture()throws Exception{
  ExportValue.Budget budget=new ExportValue.Budget(100);assertThrows(SQLException.class,()->ExportValue.snapshot(new byte[101],budget));
  List<QueryHeader> headers=new ArrayList<>(Arrays.asList(header("secret",Types.VARCHAR),header("bad",Types.OTHER)));
  SQLException e=assertThrows(SQLException.class,()->Sm4ExportRows.collect(reader(new Object[][]{{"secret",new Object()}}),headers,"u","d",policy(),Collections.emptyList(),Telemetry.begin(Observation.Operation.ENCRYPT_QUERY,null,null),body->{fail("must not capture");return "";}));
  assertTrue(e.getMessage().contains("第 2 列"));assertTrue(e.getMessage().contains("普通列"));assertTrue(e.getMessage().contains("JDBC"));assertFalse(e.getMessage().contains("original-secret"));
 }
 @Test void strictModeChecksOrdinaryContentButDoesNotTransmitIt()throws Exception{
  Map<String,Object> policy=policy();policy.put("allowEdit",false);
  List<QueryHeader> headers=new ArrayList<>(Arrays.asList(header("secret",Types.VARCHAR),header("date",Types.DATE)));
  Sm4ExportRows.collect(reader(new Object[][]{{"secret",LocalDate.of(2026,10,21)}}),headers,"u","d",policy,Collections.emptyList(),Telemetry.begin(Observation.Operation.ENCRYPT_QUERY,null,null),body->{Map<?,?> r=json.readValue(body,Map.class);assertEquals(false,r.get("allowEdit"));assertFalse(body.contains("2026-10-21"));assertEquals(1,((List<?>)r.get("digests")).size());return "{\"envelopes\":[\"__GW_SM4_V1__:synthetic\"]}";});
 }
 @Test void reportedLimitIsActualRowsAndNoPartialCaptureOccurs()throws Exception{
  Map<String,Object> p=policy();p.put("maxRows",1);
  assertThrows(SQLException.class,()->Sm4ExportRows.collect(reader(new Object[][]{{"x"},{"y"}}),new ArrayList<>(Collections.singletonList(header("secret",Types.VARCHAR))),"u","d",p,Collections.emptyList(),Telemetry.begin(Observation.Operation.ENCRYPT_QUERY,null,null),body->{fail("must not capture excess rows");return "";}));
 }
 @Test void protectedTypesReturnTextWhileCaptureRetainsSourceMetadata()throws Exception{
  int[] types={Types.DATE,Types.TIMESTAMP,Types.DOUBLE,Types.BOOLEAN,Types.VARBINARY,Types.OTHER};
  for(int type:types){
   List<QueryHeader> headers=new ArrayList<>(Collections.singletonList(header("protected_value",type)));
   Sm4ExportRows result=Sm4ExportRows.collect(reader(new Object[][]{{"secret"}}),headers,"u","d",policy(),Collections.emptyList(),Telemetry.begin(Observation.Operation.ENCRYPT_QUERY,null,null),body->{
    Map<?,?> request=json.readValue(body,Map.class);assertEquals(Collections.singletonList(type),request.get("types"));
    assertEquals(type,headers.get(0).getColumnType());return "{\"envelopes\":[\"__GW_SM4_V1__:synthetic\"]}";
   });
   assertTrue(result.next());assertEquals("masked",result.value(0));assertEquals(Types.VARCHAR,headers.get(0).getColumnType());
   assertEquals("protected_value",headers.get(0).getColumnLabel());assertEquals("protected_value",headers.get(0).getColumnName());
  }
  assertEquals(Observation.Reason.TYPE_UNSUPPORTED,Telemetry.classify(new SQLException("unsupported","0A000")));
  assertEquals(Observation.Reason.LIMIT_EXCEEDED,Telemetry.classify(new SQLException("limit","54000")));
 }
}
