package com.example.doris.gateway.security;
import com.example.doris.gateway.observability.*;
import static com.example.doris.gateway.observability.Observation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.util.*;

class CapacityMetricsTest {
 @Test void cgroupUnlimitedAndMissingResourcesAreNotZeroLimits(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)throws Exception{
  java.nio.file.Files.write(root.resolve("memory.current"),"1024".getBytes("US-ASCII"));
  java.nio.file.Files.write(root.resolve("memory.max"),"max".getBytes("US-ASCII"));
  java.nio.file.Files.write(root.resolve("cpu.max"),"200000 100000".getBytes("US-ASCII"));
  java.nio.file.Files.write(root.resolve("cpu.stat"),"usage_usec 3000000\nthrottled_usec 1000000".getBytes("US-ASCII"));
  java.nio.file.Files.write(root.resolve("memory.events"),"oom_kill 2".getBytes("US-ASCII"));
  String result=ContainerMetrics.scrape(root,"proxy");
  assertTrue(result.contains("cpu_limit_cores{component=\"proxy\"} 2.0"));
  assertTrue(result.contains("cpu_throttled_seconds_total{component=\"proxy\"} 1.0"));
  assertFalse(result.contains("memory_limit_bytes"));
  assertTrue(ContainerMetrics.scrape(root.resolve("missing"),"worker").contains("memory_available{component=\"worker\"} 0.0"));
 }
 @Test void cumulativeSurvivesHistoryEvictionAndListenerFailureAndSeparatesFailures(){
  Recorder recorder=new Recorder(1,10,1000);recorder.onComplete(r->{throw new IllegalStateException();});
  for(int i=0;i<10;i++){Observation o=recorder.begin(Operation.ENCRYPT_QUERY,null,null);o.add(Measure.ROWS,100);o.finish(i==9?Reason.TIMEOUT:Reason.OK);}
  String metrics=recorder.cumulative.scrape("proxy",System.currentTimeMillis());
  assertTrue(metrics.contains("gateway_export_measure_total{component=\"proxy\",operation=\"ENCRYPT_QUERY\",outcome=\"SUCCESS\",measure=\"ROWS\"} 900.0"));
  assertTrue(metrics.contains("outcome=\"DEPENDENCY_ERROR\",measure=\"ROWS\"} 100.0"));
  assertEquals(1,recorder.recent(0,100).size());assertFalse(metrics.contains("requestId"));
 }
 @Test void minuteMaxExpiresRatherThanReportingLifetimeMax(){
  Recorder recorder=new Recorder(2,2,1000);Observation o=recorder.begin(Operation.DECRYPT_FILE,null,null);o.finish(Reason.OK);
  CapacityMetrics metrics=new CapacityMetrics();metrics.record(recorder.recent(0,1).get(0),120001);
  String first=metrics.scrape("worker",180001);assertTrue(first.contains("gateway_export_last_minute_max_seconds"));
  String later=metrics.scrape("worker",300001);assertTrue(later.contains("outcome=\"SUCCESS\"} 0.0"));
 }
 @Test void localEndpointRequiresIndependentCredentialsAndRejectsWrites()throws Exception{
  String pass="test-private-metrics-password-123456";
  try(LocalMetricsServer server=new LocalMetricsServer(0,pass,()->"test_metric 1\n")){
   URL url=new URL("http://127.0.0.1:"+server.port()+"/metrics");HttpURLConnection c=(HttpURLConnection)url.openConnection();assertEquals(401,c.getResponseCode());c.disconnect();
   c=(HttpURLConnection)url.openConnection();c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString(("gateway_metrics:"+pass).getBytes("UTF-8")));assertEquals(200,c.getResponseCode());c.disconnect();
   c=(HttpURLConnection)url.openConnection();c.setRequestMethod("POST");c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString(("gateway_metrics:"+pass).getBytes("UTF-8")));assertEquals(404,c.getResponseCode());c.disconnect();
  }
 }
}
