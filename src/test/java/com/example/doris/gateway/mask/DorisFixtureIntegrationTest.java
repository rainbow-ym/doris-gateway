package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_DORIS_INTEGRATION", matches = "(?i)true")
class DorisFixtureIntegrationTest {

    private static final String DATABASE = "gateway_mask_test";

    @Test
    void shouldCreateIsolatedFixtureAndSupportPlaintextLikeQuery() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE);
            statement.execute("DROP TABLE IF EXISTS " + DATABASE + ".customer");
            statement.execute("CREATE TABLE " + DATABASE + ".customer ("
                    + "id BIGINT NOT NULL, id_card VARCHAR(32), name VARCHAR(64), "
                    + "phone VARCHAR(32), address VARCHAR(256), secret_value VARCHAR(128)) "
                    + "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 "
                    + "PROPERTIES (\"replication_num\" = \"1\")");
        }
        insertFixtureRows();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id_card, phone, address FROM " + DATABASE + ".customer "
                        + "WHERE id_card LIKE ? AND phone LIKE ? AND address LIKE ? ORDER BY id")) {
            statement.setString(1, "%0101%");
            statement.setString(2, "%0013%");
            statement.setString(3, "%建国路%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("110101199001011234", resultSet.getString(1));
                assertEquals("13800138000", resultSet.getString(2));
                assertEquals("北京市朝阳区建国路88号", resultSet.getString(3));
            }
        }
    }

    private void insertFixtureRows() throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + DATABASE + ".customer "
                        + "(id, id_card, name, phone, address, secret_value) VALUES (?, ?, ?, ?, ?, ?)")) {
            addRow(statement, 1L, "110101199001011234", "张三", "13800138000", "北京市朝阳区建国路88号", "payload-one");
            addRow(statement, 2L, "440101200212123456", "欧阳小明", "18612345678", "上海市浦东新区世纪大道100号", "payload-two");
            statement.executeBatch();
        }
    }

    private void addRow(final PreparedStatement statement, final long id, final String idCard, final String name,
                        final String phone, final String address, final String secretValue) throws SQLException {
        statement.setLong(1, id);
        statement.setString(2, idCard);
        statement.setString(3, name);
        statement.setString(4, phone);
        statement.setString(5, address);
        statement.setString(6, secretValue);
        statement.addBatch();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(requiredEnvironment("DORIS_JDBC_URL"), requiredEnvironment("DORIS_USER"), requiredEnvironment("DORIS_PASSWORD"));
    }

    private String requiredEnvironment(final String name) {
        String result = System.getenv(name);
        if (null == result || result.trim().isEmpty()) {
            throw new IllegalStateException("Environment variable '" + name + "' is required.");
        }
        return result;
    }
}
