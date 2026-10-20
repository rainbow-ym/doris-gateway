package com.example.doris.gateway.observability;

/** Numeric/enum-only boundary: never pass SQL, cells, envelopes, credentials or exceptions. */
public interface Observation {
    enum Operation { ENCRYPT_QUERY, DECRYPT_FILE }
    enum Stage { POLICY, RESULT_COLLECT, SERIALIZE, WORKER_CALL, ACCOUNT_CHECK,
        SOURCE_CHECK, KEY_LOAD, SM4_ENCRYPT, SM4_DECRYPT, RECEIPT_CHECK,
        PERMISSION_CHECK, DB_CONNECT, SYSTEM_DB, RECEIPT_WRITE, AUDIT_WRITE,
        TRANSACTION, CSV_PARSE, RESTORE, APPEND_COLUMN, RESULT_SEND, CLEANUP }
    enum Measure { ROWS, PROTECTED_CELLS, RAW_BYTES, PAYLOAD_BYTES, HTTP_BYTES,
        CIPHERTEXT_BYTES, DB_CONNECTIONS, PERMISSION_PROBES, SYSTEM_DB_CALLS }
    enum Outcome { SUCCESS, SKIPPED, SECURITY_DENIED, INPUT_REJECTED, BUSY,
        DEPENDENCY_ERROR, CANCELLED, INTERNAL_ERROR }
    enum Reason {
        OK(Outcome.SUCCESS), DISABLED(Outcome.SKIPPED), NOT_APPLICABLE(Outcome.SKIPPED),
        TAMPERED(Outcome.SECURITY_DENIED), EXPIRED(Outcome.SECURITY_DENIED),
        REVOKED(Outcome.SECURITY_DENIED), ACCOUNT_CHANGED(Outcome.SECURITY_DENIED),
        SOURCE_CHANGED(Outcome.SECURITY_DENIED), PERMISSION_DENIED(Outcome.SECURITY_DENIED),
        INVALID_INPUT(Outcome.INPUT_REJECTED), LIMIT_EXCEEDED(Outcome.INPUT_REJECTED),
        CAPACITY(Outcome.BUSY), TIMEOUT(Outcome.DEPENDENCY_ERROR),
        DEPENDENCY_UNAVAILABLE(Outcome.DEPENDENCY_ERROR), CLIENT_CANCELLED(Outcome.CANCELLED),
        UNKNOWN(Outcome.INTERNAL_ERROR);
        public final Outcome outcome;
        Reason(Outcome outcome) { this.outcome = outcome; }
    }
    interface Scope extends AutoCloseable { @Override void close(); }
    Scope stage(Stage stage);
    void add(Measure measure, long value);
    /** Must be called at the real lifecycle boundary, AFTER transaction outcome is known. */
    void finish(Reason reason);
    String requestId();
    String spanId();

    Observation NONE = new Observation() {
        public Scope stage(Stage stage) { return () -> { }; }
        public void add(Measure measure, long value) { }
        public void finish(Reason reason) { }
        public String requestId() { return ""; }
        public String spanId() { return ""; }
    };
}
