package com.example.doris.gateway.security;
import com.example.doris.gateway.lineage.AuditOutbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AuditOutboxTest {
    @TempDir Path root;
    @Test void failedDeliverySurvivesNewInstanceAndRetriesOnlyMessage() throws Exception {
        String id=UUID.randomUUID().toString(),payload="{\"requestId\":\""+id+"\"}";
        AuditOutbox before=new AuditOutbox(root,p->{throw new IllegalStateException("offline");});
        assertThrows(IllegalStateException.class,()->before.record(id,"EXECUTED",payload));
        assertTrue(Files.exists(root.resolve(id+"-EXECUTED.pending")));
        List<String> sent=new ArrayList<>();AuditOutbox reopened=new AuditOutbox(root,sent::add);reopened.retry();reopened.retry();
        assertEquals(Collections.singletonList(payload),sent);assertFalse(Files.exists(root.resolve(id+"-EXECUTED.pending")));
    }
    @Test void conflictingPayloadCannotOverwriteExistingAudit() {
        String id=UUID.randomUUID().toString();AuditOutbox queue=new AuditOutbox(root,p->{throw new IllegalStateException();});
        assertThrows(IllegalStateException.class,()->queue.record(id,"UNKNOWN","first"));
        assertThrows(IllegalStateException.class,()->queue.record(id,"UNKNOWN","different"));
        List<String> sent=new ArrayList<>();new AuditOutbox(root,sent::add).retry();assertEquals(Collections.singletonList("first"),sent);
    }
    @Test void oversizedMessageFailsBeforeDelivery() {
        AuditOutbox queue=new AuditOutbox(root,p->fail("must not send"));
        assertThrows(IllegalStateException.class,()->queue.record(UUID.randomUUID().toString(),"STARTED",String.join("",Collections.nCopies(16385,"x"))));
    }
}
