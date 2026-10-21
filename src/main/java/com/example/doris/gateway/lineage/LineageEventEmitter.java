package com.example.doris.gateway.lineage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Synchronous managed-object checks plus compact OpenLineage-compatible events. */
public final class LineageEventEmitter {

    private static final String ENDPOINT = environment("GATEWAY_LINEAGE_ENDPOINT");
    private static final String CONTROL_ENDPOINT = environment("GATEWAY_CONTROL_ENDPOINT", controlEndpoint(ENDPOINT));
    private static final String TOKEN = environment("GATEWAY_LINEAGE_TOKEN");
    private static final int TIMEOUT_MILLIS = integerEnvironment("GATEWAY_LINEAGE_TIMEOUT_MS", 5000);
    private static final int EXPORT_TIMEOUT_MILLIS = integerEnvironment("SM4_EXPORT_TIMEOUT_MS", 60000);
    private static final boolean FAIL_CLOSED = Boolean.parseBoolean(environment("GATEWAY_LINEAGE_FAIL_CLOSED", "true"));
    private static final AuditOutbox AUDIT_OUTBOX = new AuditOutbox(java.nio.file.Paths.get(environment("GATEWAY_AUDIT_OUTBOX_DIR","/opt/gateway/logs/audit-outbox")),
            payload -> post(CONTROL_ENDPOINT+"/audit",payload,true));
    static {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gateway-audit-retry");t.setDaemon(true);return t;})
                .scheduleWithFixedDelay(AUDIT_OUTBOX::retry,10,10,java.util.concurrent.TimeUnit.SECONDS);
    }

    private LineageEventEmitter() {
    }

    public static String accountRequest(final String operation, final String username, final String database) {
        if (CONTROL_ENDPOINT.isEmpty()) throw new IllegalStateException("缺少网关控制接口，无法验证用户身份。");
        requireToken();
        return post(CONTROL_ENDPOINT + "/" + operation, '{' + field("username", username) + ',' + field("database", database) + '}');
    }
    public static String captureExport(final String payload) {
        return captureExport(payload,null,null);
    }
    public static String captureExport(final String payload,final String requestId,final String parentSpanId) {
        if(CONTROL_ENDPOINT.isEmpty())throw new IllegalStateException("缺少加密导出控制接口。");
        java.net.URI uri=java.net.URI.create(CONTROL_ENDPOINT);
        if(!"https".equalsIgnoreCase(uri.getScheme())&&!("http".equalsIgnoreCase(uri.getScheme())&&java.util.Arrays.asList("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost())))
            throw new IllegalStateException("原值加密传输仅允许本机回环 HTTP 或 HTTPS 控制接口。");
        requireToken();
        return post(CONTROL_ENDPOINT+"/export-capture",payload,true,requestId,parentSpanId);
    }

    public static void auditExecution(final String username, final String database, final String sql, final String outcome, final String requestId) {
        if(TOKEN.length()<24)throw new IllegalStateException("审计控制凭据未正确配置，拒绝执行。");
        AUDIT_OUTBOX.record(requestId,outcome,'{' + field("username", username) + ',' + field("database", database)
                + ',' + field("sqlDigest", sha256(sql)) + ',' + field("outcome", outcome) + ',' + field("requestId", requestId) + '}');
    }

    public static PreparedExecution prepareExecution(final String storageUnit, final String sql) {
        if (CONTROL_ENDPOINT.isEmpty()) {
            fail("GATEWAY_CONTROL_ENDPOINT is required for managed-object enforcement.", null);
            return new PreparedExecution("", "BYPASS");
        }
        requireToken();
        String payload = '{' + field("storageUnit", storageUnit) + ',' + field("rawSql", sql) + '}';
        String body = post(CONTROL_ENDPOINT + "/prepare", payload);
        return new PreparedExecution(jsonValue(body, "executionId"), jsonValue(body, "statementType"));
    }

    public static void emitQuery(final String logicalDatabase, final List<Edge> edges) {
        if (edges.isEmpty() || ENDPOINT.isEmpty()) return;
        emit("QUERY", logicalDatabase, "SELECT", "", edges, null, null);
    }

    public static void emitExecutedSql(final String storageUnit, final String sql, final String executionId, final String statementType) {
        if (statementType.isEmpty() || executionId == null || executionId.isEmpty()) return;
        emit(("INSERT_SELECT".equals(statementType) || "UPDATE".equals(statementType)) ? "DML" : "DDL", "", statementType, storageUnit,
                new ArrayList<Edge>(), sql, executionId);
    }

    public static void abortExecution(final String executionId, final Exception cause) {
        if (executionId == null || executionId.isEmpty()) return;
        String message = cause == null || cause.getMessage() == null ? "Doris execution failed." : cause.getMessage();
        boolean uncertain = com.example.doris.gateway.security.ExecutionConfirmation.uncertain(cause);
        post(CONTROL_ENDPOINT + '/' + urlPath(executionId) + "/abort", '{' + field("message", message) + ',' + field("uncertain", String.valueOf(uncertain)) + '}');
    }

    private static void emit(final String eventType, final String logicalDatabase, final String statementType,
                             final String storageUnit, final List<Edge> edges, final String rawSql, final String executionId) {
        if (ENDPOINT.isEmpty()) return;
        requireToken();
        String eventId = UUID.randomUUID().toString();
        String digestSource = rawSql == null ? edges.toString() : normalizeSql(rawSql);
        StringBuilder json = new StringBuilder(1024);
        json.append('{').append(field("eventId", eventId)).append(',')
                .append(field("eventType", eventType)).append(',')
                .append(field("eventTime", Instant.now().toString())).append(',')
                .append(field("producer", "doris-security-gateway/0.3.0")).append(',')
                .append(field("schemaURL", "https://openlineage.io/spec/2-0-2/OpenLineage.json")).append(',')
                .append(field("logicalDatabase", logicalDatabase)).append(',')
                .append(field("storageUnit", storageUnit)).append(',')
                .append(field("statementType", statementType)).append(',')
                .append(field("sqlDigest", sha256(digestSource))).append(',')
                .append("\"run\":{\"runId\":\"").append(escape(eventId)).append("\"},")
                .append("\"job\":{\"namespace\":\"doris-security-gateway\",\"name\":\"")
                .append(escape(statementType.toLowerCase())).append("\"},")
                .append("\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) json.append(',');
            json.append(edges.get(i).toJson());
        }
        json.append(']');
        if (rawSql != null) json.append(',').append(field("rawSql", rawSql));
        if (executionId != null) json.append(',').append(field("executionId", executionId));
        json.append('}');
        post(ENDPOINT, json.toString());
    }

    public static String accountCommand(String actor,String database,String sql) {
        return post(CONTROL_ENDPOINT + "/account-command", '{' + field("actor",actor) + ',' + field("database",database) + ',' + field("sql",sql) + '}');
    }

    private static String post(final String endpoint, final String payload) {
        return post(endpoint,payload,false);
    }

    private static String post(final String endpoint, final String payload, final boolean strict) {
        return post(endpoint,payload,strict,null,null);
    }
    private static String post(final String endpoint, final String payload, final boolean strict, final String requestId, final String parentSpanId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(endpoint.endsWith("/export-capture") ? EXPORT_TIMEOUT_MILLIS : endpoint.endsWith("/account-command") ? 30000 : TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("X-Gateway-Lineage-Token", TOKEN);
            if(requestId!=null&&!requestId.isEmpty())connection.setRequestProperty("X-Gateway-Observation-Request-Id",requestId);
            if(parentSpanId!=null&&!parentSpanId.isEmpty())connection.setRequestProperty("X-Gateway-Observation-Parent-Span-Id",parentSpanId);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                String message = jsonValue(response, "message");
                if(strict)throw new IllegalStateException(endpoint.endsWith("/export-capture")&&!message.isEmpty()?message:"控制接口未确认，HTTP "+status);
                fail(message.isEmpty() ? "Gateway control endpoint returned HTTP " + status : message, null);
            }
            return response;
        } catch (final Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            if(strict){
                if(endpoint.endsWith("/export-capture")&&ex instanceof java.net.SocketTimeoutException)throw new IllegalStateException("控制接口超时（加密响应等待上限 "+EXPORT_TIMEOUT_MILLIS+" ms）；请检查 Worker 耗时及网络。",ex);
                throw new IllegalStateException("控制接口通信失败（"+ex.getClass().getSimpleName()+"）；请检查 Worker 状态及连接。",ex);
            }
            fail("Unable to reach gateway management control plane.", ex);
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(final InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = source.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String jsonValue(final String json, final String name) {
        if (json == null) return "";
        String marker = "\"" + name + "\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start = json.indexOf(':', start + marker.length());
        if (start < 0) return "";
        start++;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return "";
        StringBuilder result = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') return result.toString();
            if (ch == '\\' && i + 1 < json.length()) {
                char escaped = json.charAt(++i);
                if (escaped == 'n') result.append('\n');
                else if (escaped == 'r') result.append('\r');
                else if (escaped == 't') result.append('\t');
                else result.append(escaped);
            } else result.append(ch);
        }
        return "";
    }

    private static void requireToken() {
        if (TOKEN.length() < 24) fail("GATEWAY_LINEAGE_TOKEN must contain at least 24 characters.", null);
    }

    private static void fail(final String message, final Exception cause) {
        if (FAIL_CLOSED) throw new IllegalStateException(message, cause);
        System.err.println("lineage_warning=" + message + (cause == null ? "" : " cause=" + cause.getMessage()));
    }

    private static String normalizeSql(final String sql) {
        return sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
    }

    private static String field(final String name, final String value) {
        return "\"" + name + "\":\"" + escape(value == null ? "" : value) + "\"";
    }

    private static String escape(final String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
                    else result.append(ch);
            }
        }
        return result.toString();
    }

    private static String sha256(final String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte each : hash) result.append(String.format("%02x", each & 0xff));
            return result.toString();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String controlEndpoint(final String lineageEndpoint) {
        String suffix = "/lineage/events";
        return lineageEndpoint.endsWith(suffix) ? lineageEndpoint.substring(0, lineageEndpoint.length() - suffix.length()) + "/sql" : "";
    }

    private static String urlPath(final String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String environment(final String name) {
        return environment(name, "");
    }

    private static String environment(final String name, final String fallback) {
        String result = System.getenv(name);
        return result == null || result.trim().isEmpty() ? fallback : result.trim();
    }

    private static int integerEnvironment(final String name, final int fallback) {
        try {
            return Integer.parseInt(environment(name, String.valueOf(fallback)));
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    public static final class PreparedExecution {
        public final String executionId;
        public final String statementType;

        PreparedExecution(final String executionId, final String statementType) {
            this.executionId = executionId;
            this.statementType = statementType;
        }
    }

    public static final class Edge {
        public final String sourceDatabase;
        public final String sourceTable;
        public final String sourceColumn;
        public final String targetDatabase;
        public final String targetTable;
        public final String targetColumn;
        public final String transformType;

        public Edge(final String sourceDatabase, final String sourceTable, final String sourceColumn,
                    final String targetDatabase, final String targetTable, final String targetColumn, final String transformType) {
            this.sourceDatabase = sourceDatabase;
            this.sourceTable = sourceTable;
            this.sourceColumn = sourceColumn;
            this.targetDatabase = targetDatabase;
            this.targetTable = targetTable;
            this.targetColumn = targetColumn;
            this.transformType = transformType;
        }

        private String toJson() {
            return "{" + field("sourceDatabase", sourceDatabase) + ',' + field("sourceTable", sourceTable) + ','
                    + field("sourceColumn", sourceColumn) + ',' + field("targetDatabase", targetDatabase) + ','
                    + field("targetTable", targetTable) + ',' + field("targetColumn", targetColumn) + ','
                    + field("transformType", transformType) + "}";
        }

        @Override
        public String toString() {
            return sourceDatabase + '.' + sourceTable + '.' + sourceColumn + "->" + targetColumn + ':' + transformType;
        }
    }
}
