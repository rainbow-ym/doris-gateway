import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local mock server only; validates arrival-rate accounting and suppresses response values. */
public class CapacityProbeTest {
 public static void main(String[] args)throws Exception{
  assert CapacityProbe.quote("a\n\"\\").equals("\"a\\u000a\\\"\\\\\"");
  Path directory=Files.createTempDirectory("capacity-probe-test");Path csv=directory.resolve("test.csv");Files.write(csv,"col\nx\n".getBytes(StandardCharsets.UTF_8));
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);
  server.createContext("/decrypt",e->{try{while(e.getRequestBody().read()!=-1){}try{Thread.sleep(40);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}byte[] response="{\"rowCount\":1,\"csv\":\"private-value\"}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);}finally{e.close();}});server.start();
  PrintStream original=System.out;
  try{
   Properties settings=new Properties();settings.setProperty("mode","decrypt");settings.setProperty("concurrency","2");settings.setProperty("seconds","1");settings.setProperty("expectedRows","1");settings.setProperty("csv","test.csv");settings.setProperty("decryptUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/decrypt");settings.setProperty("adminUser","test");settings.setProperty("adminPassword","test-private-password");
   for(String rate:new String[]{"0","200"}){
    settings.setProperty("arrivalQps",rate);ByteArrayOutputStream output=new ByteArrayOutputStream();System.setOut(new PrintStream(output,true,"UTF-8"));new CapacityProbe(settings,directory).run();System.setOut(original);String report=output.toString("UTF-8");
    assert !report.contains("private-value")&&!report.contains("test-private-password");assert report.contains("\"failed\":0");
    if(rate.equals("200")){assert report.contains("\"offered\":200");assert !report.contains("\"clientRejected\":0");}
   }
   System.out.println("PASS: closed-loop and fixed arrival rate, rejection accounting, private values suppressed");
  }finally{System.setOut(original);server.stop(0);executor.shutdownNow();Files.delete(csv);Files.delete(directory);}
 }
}
