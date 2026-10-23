package com.example.doris.gateway.observability;

import java.util.*;
import java.util.function.LongSupplier;
import static com.example.doris.gateway.observability.Observation.*;

/** Standalone bounded in-memory implementation. No I/O, business dependencies or global context. */
public final class Recorder {
    public static final int SCHEMA_VERSION = 1;
    // Exclusive per-bucket counts; last bucket is +Inf. Exporter can make cumulative buckets.
    private static final long[] BUCKET_UPPER_MS = {1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000, 30000};
    public static long[] bucketUpperMillis() { return BUCKET_UPPER_MS.clone(); }
    private final int capacity, maxActive;
    private final long retentionNanos;
    private final LongSupplier clock;
    private final Deque<Entry> history = new ArrayDeque<>();
    private final EnumMap<Operation, long[]> histograms = new EnumMap<>(Operation.class);
    private final EnumMap<Reason, Long> results = new EnumMap<>(Reason.class);
    private long active, dropped, evicted;
    public final CapacityMetrics cumulative = new CapacityMetrics();
    private volatile java.util.function.Consumer<Record> listener = record -> { };
    public void onComplete(java.util.function.Consumer<Record> listener) { this.listener = Objects.requireNonNull(listener); }

    public Recorder(int capacity, int maxActive, long retentionMillis) {
        this(capacity, maxActive, retentionMillis, System::nanoTime);
    }
    Recorder(int capacity, int maxActive, long retentionMillis, LongSupplier clock) {
        if (capacity < 1 || capacity > 100000 || maxActive < 1 || maxActive > 10000
                || retentionMillis < 1 || retentionMillis > 86400000) throw new IllegalArgumentException("Invalid bounds");
        this.capacity = capacity; this.maxActive = maxActive;
        this.retentionNanos = retentionMillis * 1000000; this.clock = Objects.requireNonNull(clock);
        for (Operation op : Operation.values()) histograms.put(op, new long[BUCKET_UPPER_MS.length + 1]);
    }
    /** Correlation IDs only, never arbitrary user text. Caller must authenticate internal propagation. */
    public synchronized Observation begin(Operation operation, String requestId, String parentSpanId) {
        if (operation == null || !validId(requestId) || !validId(parentSpanId) || active >= maxActive) {
            dropped++; return Observation.NONE;
        }
        active++;
        return new Request(operation, requestId == null ? id() : requestId, parentSpanId);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static boolean validId(String id) {
        return id == null || id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private void purge(long now) { while (!history.isEmpty() && now - history.peekFirst().finished >= retentionNanos) history.removeFirst(); }
    /** Bounded page, newest first. A production durable cursor adapter is deliberately deferred. */
    public synchronized List<Record> recent(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page");
        purge(clock.getAsLong()); List<Record> result = new ArrayList<>(); int index = 0;
        Iterator<Entry> it = history.descendingIterator();
        while (it.hasNext() && result.size() < limit) { Record record = it.next().record; if (index++ >= offset) result.add(record); }
        return Collections.unmodifiableList(result);
    }
    public synchronized Map<String, Long> status() {
        purge(clock.getAsLong()); Map<String, Long> result = new LinkedHashMap<>();
        result.put("active", active); result.put("dropped", dropped); result.put("evicted", evicted);
        result.put("retained", (long) history.size()); return Collections.unmodifiableMap(result);
    }
    public synchronized long count(Reason reason) { return results.getOrDefault(reason, 0L); }
    public synchronized long[] histogram(Operation operation) { return histograms.get(operation).clone(); }
    private synchronized void complete(Record record, long now) {
        cumulative.record(record,System.currentTimeMillis());
        active--; results.put(record.reason, count(record.reason) + 1);
        long[] buckets = histograms.get(record.operation); int bucket = 0;
        while (bucket < BUCKET_UPPER_MS.length && record.durationNanos > BUCKET_UPPER_MS[bucket] * 1000000) bucket++;
        buckets[bucket]++; purge(now);
        if (history.size() == capacity) { history.removeFirst(); evicted++; }
        history.addLast(new Entry(now, record));
    }
    private static final class Entry {
        final long finished; final Record record;
        Entry(long finished, Record record) { this.finished = finished; this.record = record; }
    }
    public static final class Timing {
        public final long calls, totalNanos, maxNanos;
        Timing(long calls, long totalNanos, long maxNanos) { this.calls = calls; this.totalNanos = totalNanos; this.maxNanos = maxNanos; }
    }
    public static final class Record {
        public final String requestId, spanId, parentSpanId;
        public final Operation operation; public final Reason reason; public final long durationNanos;
        public final long startedAtMillis;
        public final Map<Stage, Timing> stages; public final Map<Measure, Long> measures;
        public final int unfinishedScopes;
        Record(Request r, Reason reason, long duration) {
            requestId = r.request; spanId = r.span; parentSpanId = r.parent; operation = r.operation;
            this.reason = reason; durationNanos = duration; unfinishedScopes = r.open;
            startedAtMillis = r.startedAtMillis;
            stages = Collections.unmodifiableMap(new EnumMap<>(r.stages));
            measures = Collections.unmodifiableMap(new EnumMap<>(r.measures));
        }
    }
    private final class Request implements Observation {
        final String request, span = id(), parent; final Operation operation;
        final long started = clock.getAsLong();
        final long startedAtMillis = System.currentTimeMillis();
        final EnumMap<Stage, Timing> stages = new EnumMap<>(Stage.class);
        final EnumMap<Measure, Long> measures = new EnumMap<>(Measure.class);
        int open; boolean finished;
        Request(Operation operation, String request, String parent) { this.operation = operation; this.request = request; this.parent = parent; }
        public String requestId() { return request; }
        public String spanId() { return span; }
        public synchronized Scope stage(Stage stage) {
            if (finished || stage == null || open >= 256) return () -> { };
            open++; final long start = clock.getAsLong();
            return new Scope() {
                boolean closed;
                public void close() {
                    synchronized (Request.this) {
                        if (closed) return; closed = true; open--;
                        if (finished) return;
                        long elapsed = Math.max(0, clock.getAsLong() - start);
                        Timing old = stages.get(stage);
                        stages.put(stage, new Timing(old == null ? 1 : old.calls + 1,
                            old == null ? elapsed : saturatedAdd(old.totalNanos, elapsed),
                            old == null ? elapsed : Math.max(old.maxNanos, elapsed)));
                    }
                }
            };
        }
        public synchronized void add(Measure measure, long value) {
            if (!finished && measure != null && value >= 0) measures.put(measure, saturatedAdd(measures.getOrDefault(measure, 0L), value));
        }
        public synchronized void finish(Reason reason) {
            if (finished) return; finished = true;
            long now = clock.getAsLong(); Record record = new Record(this, reason == null ? Reason.UNKNOWN : reason, Math.max(0, now - started));
            complete(record, now);
            try { listener.accept(record); } catch (RuntimeException ignored) { /* Monitoring never replaces business outcome. */ }
        }
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
