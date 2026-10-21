package com.example.doris.gateway.admin;

import org.junit.jupiter.api.*;
import com.example.doris.gateway.observability.*;
import static com.example.doris.gateway.observability.Observation.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class CryptoObservationsTest {
    CryptoObservations observations;
    @BeforeEach void setup(){observations=new CryptoObservations();observations.start();}
    @AfterEach void close(){observations.stop();}
    CryptoObservations.Event event(){CryptoObservations.Event e=new CryptoObservations.Event();e.requestId=UUID.randomUUID().toString();e.spanId=UUID.randomUUID().toString();e.operation=Operation.ENCRYPT_QUERY;e.reason=Reason.OK;e.durationNanos=10000000;e.startedAtMillis=System.currentTimeMillis();return e;}
    @Test void recordsCorrelatedTimingsAndLowCardinalityMetricsWithoutSensitiveValues(){
        Telemetry.Session s=Telemetry.begin(Operation.ENCRYPT_QUERY,null,null);
        try(Observation.Scope binding=Telemetry.bind(s);Observation.Scope stage=s.stage(Stage.SM4_ENCRYPT)){s.add(Measure.ROWS,500);}
        s.finish(null);
        assertEquals(1L,observations.summary("worker",Operation.ENCRYPT_QUERY,15).get("count"));
        assertEquals(1,observations.detail(s.requestId()).size());
        assertTrue(observations.scrape().contains("gateway_crypto_duration_seconds_bucket"));
        assertFalse(observations.scrape().contains(s.requestId()));
        assertEquals("",Telemetry.current().requestId());
    }
    @Test void deduplicatesAndSeparatesComponentsAndSecurityFailures(){
        CryptoObservations.Event e=event();e.reason=Reason.PERMISSION_DENIED;observations.accept(e,true);observations.accept(e,true);
        assertEquals(1L,observations.summary("proxy",Operation.ENCRYPT_QUERY,5).get("count"));
        assertEquals(0L,observations.summary("worker",Operation.ENCRYPT_QUERY,5).get("count"));
        assertEquals(1,observations.detail(e.requestId).size());
        assertTrue(observations.scrape().contains("outcome=\"SECURITY_DENIED\""));
    }
    @Test void boundedHistoryAndValidationDoNotLoseAggregateCounters(){
        for(int i=0;i<2005;i++)observations.accept(event(),true);
        assertEquals(2000,observations.requests("proxy",Operation.ENCRYPT_QUERY,null,1,20).get("total"));
        assertEquals(2005L,observations.summary("proxy",Operation.ENCRYPT_QUERY,60).get("count"));
        CryptoObservations.Event invalid=event();invalid.requestId="SELECT private";observations.accept(invalid,true);
        assertEquals(1L,observations.status().get("rejected"));
        assertThrows(IllegalArgumentException.class,()->observations.requests("arbitrary",Operation.ENCRYPT_QUERY,null,1,20));
        assertThrows(IllegalArgumentException.class,()->observations.requests("worker",Operation.ENCRYPT_QUERY,null,0,20));
    }
    @Test void monitoringListenerFailureCannotReplaceBusinessOutcome(){
        Telemetry.RECORDER.onComplete(r->{throw new IllegalStateException("sink unavailable");});
        Telemetry.Session s=Telemetry.begin(Operation.DECRYPT_FILE,null,null);
        assertDoesNotThrow(()->s.finish(null));assertEquals(0L,Telemetry.RECORDER.status().get("active"));
    }
    @Test void noSampleAndUnsupportedResourcesAreNotReportedAsZero(){
        assertNull(observations.summary("worker",Operation.ENCRYPT_QUERY,15).get("p95Ms"));
        assertNull(observations.status().get("containerMemory"));assertNull(observations.status().get("connectionPool"));
    }
}
