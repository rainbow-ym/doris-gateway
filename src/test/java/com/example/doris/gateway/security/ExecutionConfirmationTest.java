package com.example.doris.gateway.security;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionConfirmationTest {
    @Test void startAuditFailurePreventsExecution() {
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->ExecutionConfirmation.execute(calls::incrementAndGet,(id,state)->{throw new IllegalStateException();}));
        assertEquals(0,calls.get());
    }
    @Test void finalAuditFailureDoesNotReplayOrReportDatabaseFailure() {
        AtomicInteger calls=new AtomicInteger();List<String> states=new ArrayList<>(),ids=new ArrayList<>();
        SQLException e=assertThrows(SQLException.class,()->ExecutionConfirmation.execute(calls::incrementAndGet,(id,state)->{
            ids.add(id);states.add(state);if(state.equals("EXECUTED"))throw new IllegalStateException("private detail");
        }));
        assertEquals(1,calls.get());assertEquals(Arrays.asList("STARTED","EXECUTED"),states);
        assertEquals(ids.get(0),ids.get(1));assertTrue(e.getMessage().contains(ids.get(0)));
        assertEquals(39001,e.getErrorCode());assertEquals("HY000",e.getSQLState());assertFalse(e.getMessage().contains("private detail"));
    }
    @Test void successUsesSameCorrelationId() throws Exception {
        List<String> ids=new ArrayList<>();
        assertEquals("done",ExecutionConfirmation.execute(()->"done",(id,state)->ids.add(id)));
        assertEquals(2,ids.size());assertEquals(ids.get(0),ids.get(1));
    }
    @Test void synchronizationFailureIsUnknownAndNeverRetried() {
        List<String> states=new ArrayList<>();AtomicInteger calls=new AtomicInteger();
        SQLException e=assertThrows(SQLException.class,()->ExecutionConfirmation.execute(()->{
            calls.incrementAndGet();throw new ExecutionConfirmation.PostExecutionFailure("exec-1",new IllegalStateException());
        },(id,state)->states.add(state)));
        assertEquals(39001,e.getErrorCode());assertEquals(1,calls.get());assertEquals(Arrays.asList("STARTED","UNKNOWN"),states);
    }
    @Test void ordinarySqlErrorPreservedEvenWhenFailureAuditBreaks() {
        SQLException original=new SQLException("Access denied","42000");
        SQLException result=assertThrows(SQLException.class,()->ExecutionConfirmation.execute(()->{throw original;},(id,state)->{
            if(state.equals("UNKNOWN"))throw new IllegalStateException();
        }));
        assertSame(original,result);assertEquals(1,result.getSuppressed().length);
    }
    @Test void nestedAndNextExceptionsIncludeTimeoutAndUnknownCompletion() {
        for(String state:Arrays.asList("08006","HYT00","HYT01","40003")) {
            SQLException outer=new SQLException("wrapper","HY000");outer.setNextException(new SQLException("nested",state));
            assertTrue(ExecutionConfirmation.uncertain(outer));
        }
        assertTrue(ExecutionConfirmation.uncertain(new RuntimeException(new SQLTimeoutException())));
        assertTrue(ExecutionConfirmation.uncertain(new SQLRecoverableException()));
        assertFalse(ExecutionConfirmation.uncertain(new SQLException("syntax","42000")));
        RuntimeException a=new RuntimeException(),b=new RuntimeException(a);a.initCause(b);
        assertFalse(ExecutionConfirmation.uncertain(a));
    }
}
