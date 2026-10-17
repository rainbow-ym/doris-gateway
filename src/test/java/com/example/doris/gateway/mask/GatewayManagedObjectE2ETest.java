package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_GATEWAY_INTEGRATION", matches = "(?i)true")
class GatewayManagedObjectE2ETest {

    @Test
    void shouldRegisterCreateAndAutoProtectRecognizedSensitiveColumns() throws Exception {
        direct("DROP TABLE IF EXISTS managed_create_probe");
        try (Connection connection = gateway(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE managed_create_probe (id BIGINT, id_card VARCHAR(32), name VARCHAR(64))");
            statement.execute("INSERT INTO managed_create_probe VALUES (1,'110101199001011234','张三')");
            try (ResultSet result = statement.executeQuery("SELECT id_card,name FROM managed_create_probe WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("110101********1234", result.getString(1));
                assertEquals("张*", result.getString(2));
            }
            statement.execute("DROP TABLE managed_create_probe");
        }
    }

    @Test
    void shouldRejectObjectCreatedOutsideGatewayUntilReconciled() throws Exception {
        direct("DROP TABLE IF EXISTS unmanaged_probe");
        direct("CREATE TABLE unmanaged_probe (id BIGINT, id_card VARCHAR(32))");
        try (Connection connection = gateway(); Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT id_card FROM unmanaged_probe"));
            assertTrue(error.getMessage().toLowerCase().contains("does not exist") || error.getSQLState().startsWith("42"));
        } finally {
            direct("DROP TABLE IF EXISTS unmanaged_probe");
        }
    }

    @Test
    void shouldApproveScannedObjectAndPublishRecommendedProtection() throws Exception {
        direct("DROP TABLE IF EXISTS unmanaged_approval_probe");
        direct("CREATE TABLE unmanaged_approval_probe (id BIGINT, id_card VARCHAR(32), name VARCHAR(64))");
        try {
            http("POST", "/api/connections/1/scan");
            String objects = http("GET", "/api/managed-objects");
            Matcher match = Pattern.compile("\\{\\\"id\\\":(\\d+)[^}]*\\\"tableName\\\":\\\"unmanaged_approval_probe\\\"").matcher(objects);
            assertTrue(match.find(), objects);
            http("POST", "/api/managed-objects/" + match.group(1) + "/approve");
            try (Connection connection = gateway(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO unmanaged_approval_probe VALUES (1,'110101199001011234','张三')");
                try (ResultSet result = statement.executeQuery("SELECT id_card,name FROM unmanaged_approval_probe WHERE id=1")) {
                    assertTrue(result.next());
                    assertEquals("110101********1234", result.getString(1));
                    assertEquals("张*", result.getString(2));
                }
                statement.execute("DROP TABLE unmanaged_approval_probe");
            }
        } finally {
            direct("DROP TABLE IF EXISTS unmanaged_approval_probe");
        }
    }

    private static Connection gateway() throws Exception {
        return DriverManager.getConnection(System.getenv("GATEWAY_JDBC_URL"), System.getenv("GATEWAY_USER"), System.getenv("GATEWAY_PASSWORD"));
    }

    private static void direct(final String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("DORIS_JDBC_URL"), System.getenv("DORIS_USER"), System.getenv("DORIS_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String http(final String method, final String path) throws Exception {
        String base = System.getenv("GATEWAY_ADMIN_URL");
        if (base == null || base.isEmpty()) base = "http://127.0.0.1:18080";
        String username = System.getenv("GATEWAY_ADMIN_USER");
        String password = System.getenv("GATEWAY_ADMIN_PASSWORD");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8)));
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while (input != null && (count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        if (input != null) input.close();
        connection.disconnect();
        String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status + ": " + body);
        return body;
    }
}
