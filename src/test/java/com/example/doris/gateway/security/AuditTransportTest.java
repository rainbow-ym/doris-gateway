package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AuditTransportTest {
    @Test void httpFailureCannotSilentlyBecomeSuccessEvenWithLegacyFailOpenSetting() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name").startsWith("Windows"),
                "Durable audit directory fsync requires the Linux deployment filesystem");
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/audit",exchange->{
            int n=requests.incrementAndGet();
            while(exchange.getRequestBody().read()!=-1) { }
            byte[] body="{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n==1?200:503,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        Process process=null;
        try {
            // The child needs only application/test classes, not the entire Surefire boot classpath.
            String cp=new java.io.File(AuditTransportTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
                    +java.io.File.pathSeparator+new java.io.File(LineageEventEmitter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            ProcessBuilder builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",cp,AuditTransportTest.class.getName());
            builder.environment().put("GATEWAY_CONTROL_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort());
            builder.environment().put("GATEWAY_LINEAGE_TOKEN","synthetic-test-token-not-a-secret");
            builder.environment().put("GATEWAY_LINEAGE_FAIL_CLOSED","false");
            builder.environment().put("GATEWAY_AUDIT_OUTBOX_DIR",java.nio.file.Files.createTempDirectory("audit-transport-").toString());
            process=builder.inheritIO().start();assertTrue(process.waitFor(20,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue());assertEquals(2,requests.get());
        } finally {if(process!=null&&process.isAlive())process.destroyForcibly();server.stop(0);}
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger executions=new AtomicInteger();
        try {
            ExecutionConfirmation.execute(executions::incrementAndGet,(id,state)->LineageEventEmitter.auditExecution("test","db","SELECT synthetic",state,id));
            throw new AssertionError("completion audit failure was ignored");
        } catch(SQLException error) {
            if(error.getErrorCode()!=39001||executions.get()!=1)throw new AssertionError("unexpected execution or acknowledgement");
        }
    }
}
