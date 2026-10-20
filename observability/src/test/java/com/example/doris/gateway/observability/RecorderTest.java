package com.example.doris.gateway.observability;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.example.doris.gateway.observability.Observation.*;

/** No external test dependency: java -ea ...RecorderTest. */
public final class RecorderTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Assertion " + assertions); }
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(); Recorder recorder = new Recorder(2, 2, 1000, clock::get);
        Observation a = recorder.begin(Operation.ENCRYPT_QUERY, null, null);
        Observation b = recorder.begin(Operation.ENCRYPT_QUERY, a.requestId(), a.spanId());
        check(a.requestId().equals(b.requestId())); check(!a.spanId().equals(b.spanId()));
        check(recorder.begin(Operation.ENCRYPT_QUERY, null, null) == Observation.NONE);
        check(recorder.status().get("dropped") == 1);
        Scope scope = a.stage(Stage.SM4_ENCRYPT); clock.addAndGet(2000000); scope.close(); scope.close();
        a.add(Measure.ROWS, 500); a.add(Measure.ROWS, -1); a.finish(Reason.OK); a.finish(Reason.UNKNOWN);
        Recorder.Record record = recorder.recent(0, 1).get(0);
        check(record.measures.get(Measure.ROWS) == 500); check(record.stages.get(Stage.SM4_ENCRYPT).calls == 1);
        check(record.stages.get(Stage.SM4_ENCRYPT).totalNanos == 2000000);
        check(recorder.count(Reason.OK) == 1); check(recorder.histogram(Operation.ENCRYPT_QUERY)[1] == 1);
        long[] copy = recorder.histogram(Operation.ENCRYPT_QUERY); copy[1] = 900;
        check(recorder.histogram(Operation.ENCRYPT_QUERY)[1] == 1);
        long[] bounds = Recorder.bucketUpperMillis(); bounds[0] = 999;
        check(Recorder.bucketUpperMillis()[0] == 1);
        try { record.measures.put(Measure.ROWS, 0L); throw new AssertionError("Mutable record"); }
        catch (UnsupportedOperationException expected) { check(true); }
        Scope open = b.stage(Stage.PERMISSION_CHECK); b.finish(Reason.PERMISSION_DENIED); open.close();
        check(recorder.recent(0, 1).get(0).unfinishedScopes == 1);
        check(recorder.recent(0, 1).get(0).reason.outcome == Outcome.SECURITY_DENIED);
        check(recorder.status().get("active") == 0);
        Observation c = recorder.begin(Operation.DECRYPT_FILE, null, null); c.finish(Reason.CLIENT_CANCELLED);
        check(recorder.status().get("evicted") == 1); check(recorder.recent(0, 100).size() == 2);
        check(recorder.recent(1, 1).size() == 1); check(recorder.recent(99, 1).isEmpty());
        clock.addAndGet(1000000001); check(recorder.recent(0, 100).isEmpty());
        check(recorder.count(Reason.OK) == 1); // Retention does not erase cumulative metrics.
        check(recorder.begin(Operation.ENCRYPT_QUERY, "SELECT secret", null) == Observation.NONE);
        Observation.NONE.stage(null).close(); Observation.NONE.finish(null);
        Recorder parallel = new Recorder(10, 100, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(8); List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) futures.add(pool.submit(() -> {
            Observation request = parallel.begin(Operation.DECRYPT_FILE, null, null);
            try (Scope ignored = request.stage(Stage.SM4_DECRYPT)) { request.add(Measure.ROWS, 1); }
            request.finish(Reason.OK);
        }));
        for (Future<?> future : futures) future.get(); pool.shutdown();
        check(parallel.count(Reason.OK) == 1000); check(parallel.status().get("active") == 0);
        check(parallel.status().get("retained") <= 10);
        Recorder race = new Recorder(2, 2, 1000); Observation shared = race.begin(Operation.ENCRYPT_QUERY, null, null);
        ExecutorService closers = Executors.newFixedThreadPool(4); List<Future<?>> ends = new ArrayList<>();
        Scope sharedScope = shared.stage(Stage.SM4_ENCRYPT);
        for (int i = 0; i < 100; i++) ends.add(closers.submit(() -> { sharedScope.close(); shared.finish(Reason.OK); }));
        for (Future<?> future : ends) future.get(); closers.shutdown();
        check(race.count(Reason.OK) == 1); check(race.status().get("active") == 0);
        check(race.recent(0, 1).get(0).stages.get(Stage.SM4_ENCRYPT).calls == 1);
        Recorder histogram = new Recorder(2, 2, 1000, clock::get);
        Observation exact = histogram.begin(Operation.DECRYPT_FILE, null, null);
        clock.addAndGet(1000000); exact.finish(Reason.OK);
        check(histogram.histogram(Operation.DECRYPT_FILE)[0] == 1);
        Observation overflow = histogram.begin(Operation.DECRYPT_FILE, null, null);
        clock.addAndGet(31000000000L); overflow.finish(Reason.TIMEOUT);
        check(histogram.histogram(Operation.DECRYPT_FILE)[11] == 1);
        System.out.println("PASS: " + assertions + " assertions; 1000 concurrent requests");
    }
}
