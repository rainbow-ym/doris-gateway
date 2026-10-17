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
            ProcessBuilder builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),AuditTransportTest.class.getName());
            builder.environment().put("GATEWAY_CONTROL_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort());
            builder.environment().put("GATEWAY_LINEAGE_TOKEN","synthetic-test-token-not-a-secret");
            builder.environment().put("GATEWAY_LINEAGE_FAIL_CLOSED","false");
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
