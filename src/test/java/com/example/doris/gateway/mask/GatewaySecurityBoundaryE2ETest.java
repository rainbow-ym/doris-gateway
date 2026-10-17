package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_GATEWAY_INTEGRATION", matches = "(?i)true")
class GatewaySecurityBoundaryE2ETest {

    @Test
    void shouldMaskTableAlias() throws SQLException {
        assertSingleValue("SELECT c.id_card FROM customer c WHERE c.id = 1", "110101********1234");
    }

    @Test
    void shouldMaskStarExpansion() throws SQLException {
        try (Connection connection = openGateway(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT c.* FROM customer c WHERE c.id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("110101********1234", resultSet.getString("id_card"));
            assertEquals("张*", resultSet.getString("name"));
        }
    }

    @Test
    void shouldMaskCommonTableExpressionAlias() throws SQLException {
        assertSingleValue("WITH x AS (SELECT id, id_card AS credential FROM customer) "
                + "SELECT credential FROM x WHERE id = 1", "110101********1234");
    }

    @Test
    void shouldRejectSensitiveFunctionProjection() {
        assertUnsafeProjection("SELECT CONCAT('', id_card) AS credential FROM customer WHERE id = 1");
    }

    @Test
    void shouldRejectSensitiveCaseProjection() {
        assertUnsafeProjection("SELECT CASE WHEN id = 1 THEN id_card ELSE 'none' END AS credential "
                + "FROM customer WHERE id = 1");
    }

    @Test
    void shouldRejectSensitiveAggregationProjection() {
        assertUnsafeProjection("SELECT MAX(id_card) FROM customer");
    }

    @Test
    void shouldAllowNonSensitiveConstantAndCount() throws SQLException {
        assertSingleValue("SELECT 'ok'", "ok");
        assertSingleValue("SELECT COUNT(*) FROM customer", "2");
    }

    private void assertUnsafeProjection(final String sql) {
        SQLException exception = assertThrows(SQLException.class, () -> {
            try (Connection connection = openGateway(); Statement statement = connection.createStatement()) {
                statement.executeQuery(sql);
            }
        });
        assertTrue(exception.getMessage().contains("Sensitive projection cannot be returned"));
    }

    private void assertSingleValue(final String sql, final String expected) throws SQLException {
        try (Connection connection = openGateway(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getString(1));
        }
    }

    private Connection openGateway() throws SQLException {
        return DriverManager.getConnection(requiredEnvironment("GATEWAY_JDBC_URL"),
                requiredEnvironment("GATEWAY_USER"), requiredEnvironment("GATEWAY_PASSWORD"));
    }

    private String requiredEnvironment(final String name) {
        String result = System.getenv(name);
        if (null == result || result.trim().isEmpty()) {
            throw new IllegalStateException("Environment variable '" + name + "' is required.");
        }
        return result;
    }
}
