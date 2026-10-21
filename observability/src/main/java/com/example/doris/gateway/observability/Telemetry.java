package com.example.doris.gateway.observability;

import static com.example.doris.gateway.observability.Observation.*;

/** Synchronous Worker binding only; Proxy passes Session explicitly across boundaries. */
public final class Telemetry {
    public static final Recorder RECORDER = new Recorder(2000, 256, 3600000);
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static volatile boolean enabled = true;
    private static final Session NONE = new Session(Observation.NONE);
    private Telemetry() { }
    public static void enabled(boolean value) { enabled = value; }
    public static Session begin(Operation operation, String request, String parent) {
        try { return enabled ? new Session(RECORDER.begin(operation, request, parent)) : NONE; }
        catch (RuntimeException ignored) { return NONE; }
    }
    public static Session current() { Session result = CURRENT.get(); return result == null ? NONE : result; }
    public static Scope bind(Session session) {
        Session previous = CURRENT.get(); CURRENT.set(session);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static final class Session {
        private final Observation observation;
        private Reason reason;
        private Session(Observation observation) { this.observation = observation; }
        public String requestId() { return observation.requestId(); }
        public String spanId() { return observation.spanId(); }
        public Scope stage(Stage stage) {
            try { Scope inner = observation.stage(stage); return () -> { try { inner.close(); } catch (RuntimeException ignored) { } }; }
            catch (RuntimeException ignored) { return () -> { }; }
        }
        public void add(Measure measure, long value) { try { observation.add(measure,value); } catch (RuntimeException ignored) { } }
        public void reason(Reason value) { if (this != NONE) reason = value; }
        public void finish(Throwable failure) {
            try { observation.finish(failure == null ? Reason.OK : reason != null ? reason : classify(failure)); }
            catch (RuntimeException ignored) { }
        }
        public void finishAs(Reason value) { try { observation.finish(value); } catch (RuntimeException ignored) { } }
    }
    /** No message-based matching and no exception text stored. */
    public static Reason classify(Throwable error) {
        for (int i=0; error!=null && i<8; i++,error=error.getCause()) {
            if (error instanceof javax.crypto.AEADBadTagException) return Reason.TAMPERED;
            if (error instanceof java.net.SocketTimeoutException || error instanceof java.sql.SQLTimeoutException) return Reason.TIMEOUT;
            if (error instanceof java.sql.SQLException) {
                java.sql.SQLException sql=(java.sql.SQLException)error;
                if ("0A000".equals(sql.getSQLState())) return Reason.TYPE_UNSUPPORTED;
                if ("54000".equals(sql.getSQLState())) return Reason.LIMIT_EXCEEDED;
                if (sql.getErrorCode()==1142 || sql.getErrorCode()==1044 || sql.getErrorCode()==1045) return Reason.PERMISSION_DENIED;
                return Reason.DEPENDENCY_UNAVAILABLE;
            }
            if (error instanceof SecurityException) return Reason.PERMISSION_DENIED;
            if (error instanceof IllegalArgumentException) return Reason.INVALID_INPUT;
        }
        return Reason.UNKNOWN;
    }
}
