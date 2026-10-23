package com.example.doris.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class MetricsStartupTest {
 @Test void launcherStartsMetricsBeforeAnyDatabaseConnector(@TempDir Path temporary)throws Exception {
  Path source=temporary.resolve("Bootstrap.java");
  Files.write(source,"package org.apache.shardingsphere.proxy; public class Bootstrap { public static void main(String[] args) {} }".getBytes(StandardCharsets.UTF_8));
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",temporary.toString(),source.toString()));
  int port;try(ServerSocket reserve=new ServerSocket(0)){port=reserve.getLocalPort();}
  String password="local-startup-test-password-only";
  ProcessBuilder builder=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",temporary+java.io.File.pathSeparator+System.getProperty("java.class.path"),"com.example.doris.gateway.GatewayBootstrap");
  builder.environment().put("METRICS_PASSWORD",password);builder.environment().put("PROXY_METRICS_PORT",Integer.toString(port));builder.environment().put("GATEWAY_MULTI_USER_ENABLED","false");
  builder.redirectErrorStream(true);Process process=builder.start();
  try {
   boolean ready=false;
   for(int i=0;i<50;i++){
    try {HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/metrics").openConnection();c.setConnectTimeout(200);c.setReadTimeout(1000);c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString(("gateway_metrics:"+password).getBytes(StandardCharsets.UTF_8)));ready=c.getResponseCode()==200;c.disconnect();if(ready)break;}catch(java.io.IOException ignored){}Thread.sleep(100);
   }
   assertTrue(ready,"metrics must listen before the first query");
  }finally{process.destroy();if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);}process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}
 }
}
