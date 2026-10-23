package com.example.doris.gateway.observability;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Loopback-only, authenticated read-only endpoint; bounded threads and no request payloads. */
public final class LocalMetricsServer implements AutoCloseable {
 private final HttpServer server;private final ExecutorService executor;
 public LocalMetricsServer(int port,String password,Supplier<String> scrape)throws IOException{
  if(password==null||password.length()<24)throw new IllegalArgumentException("Metrics password must contain 24 characters");
  byte[] expected=("Basic "+Base64.getEncoder().encodeToString(("gateway_metrics:"+password).getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
  executor=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"gateway-metrics");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  server.setExecutor(executor);
  server.createContext("/metrics",exchange->{try{
   String authorization=exchange.getRequestHeaders().getFirst("Authorization");
   if(authorization==null||!MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.US_ASCII))){exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=metrics");exchange.sendResponseHeaders(401,-1);return;}
   if(!"GET".equals(exchange.getRequestMethod())||!"/metrics".equals(exchange.getRequestURI().getPath())){exchange.sendResponseHeaders(404,-1);return;}
   byte[] body=scrape.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; version=0.0.4; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
  }finally{exchange.close();}});server.start();
 }
 public int port(){return server.getAddress().getPort();}
 public void close(){server.stop(0);executor.shutdownNow();}
}
