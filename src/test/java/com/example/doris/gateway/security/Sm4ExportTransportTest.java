package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class Sm4ExportTransportTest {
    @Test void exportUsesIndependentTimeoutAndReportsTimeoutExplicitly() throws Exception {
        java.util.concurrent.atomic.AtomicInteger correlated=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/export-capture",exchange->{
            try {
                if("00000000-0000-0000-0000-000000000001".equals(exchange.getRequestHeaders().getFirst("X-Gateway-Observation-Request-Id"))
                        && "00000000-0000-0000-0000-000000000002".equals(exchange.getRequestHeaders().getFirst("X-Gateway-Observation-Parent-Span-Id")))correlated.incrementAndGet();
                while(exchange.getRequestBody().read()!=-1) { }
                Thread.sleep(300);
                byte[] body="{\"envelopes\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            runChild(server.getAddress().getPort(),"1500","success");
            runChild(server.getAddress().getPort(),"100","timeout");
            assertEquals(2,correlated.get(),"Both requests must propagate internal correlation headers");
        } finally { server.stop(0); }
    }
    private void runChild(int port,String timeout,String expectation) throws Exception {
        // Only these two output directories are needed; avoid Windows command length limits.
        String cp=new File(Sm4ExportTransportTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
                +File.pathSeparator+new File(LineageEventEmitter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        ProcessBuilder builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",cp,
                Sm4ExportTransportTest.class.getName(),expectation);
        builder.environment().put("GATEWAY_CONTROL_ENDPOINT","http://127.0.0.1:"+port);
        builder.environment().put("GATEWAY_LINEAGE_TOKEN","synthetic-test-token-not-a-secret");
        builder.environment().put("GATEWAY_LINEAGE_TIMEOUT_MS","50");
        builder.environment().put("SM4_EXPORT_TIMEOUT_MS",timeout);
        builder.environment().put("GATEWAY_AUDIT_OUTBOX_DIR",Files.createTempDirectory("sm4-transport-").toString());
        Process process=builder.inheritIO().start();
        try { assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue()); }
        finally { if(process.isAlive())process.destroyForcibly(); }
    }
    public static void main(String[] args) {
        try {
            LineageEventEmitter.captureExport("{}","00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002");
            if(!"success".equals(args[0]))throw new AssertionError("timeout was ignored");
        } catch(IllegalStateException e) {
            if(!"timeout".equals(args[0])||!e.getMessage().contains("100 ms")
                    ||!(e.getCause() instanceof java.net.SocketTimeoutException))throw e;
        }
    }
}
