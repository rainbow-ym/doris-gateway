package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_GATEWAY_INTEGRATION", matches = "(?i)true")
class GatewayMaskE2ETest {

    @Test
    void shouldMatchPlaintextAndMaskReturnedCredential() throws SQLException {
        try (Connection connection = openGateway(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id_card FROM customer WHERE id_card LIKE ? ORDER BY id")) {
            statement.setString(1, "%0101%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("110101********1234", resultSet.getString(1));
            }
        }
    }

    @Test
    void shouldMaskColumnAlias() throws SQLException {
        assertSingleValue("SELECT id_card AS credential FROM customer WHERE id = 1", "110101********1234");
    }

    @Test
    void shouldMaskSubqueryAlias() throws SQLException {
        assertSingleValue("SELECT x.credential FROM (SELECT id, id_card AS credential FROM customer) x WHERE x.id = 1", "110101********1234");
    }

    @Test
    void shouldMaskNamesByUnicodeLength() throws SQLException {
        try (Connection connection = openGateway(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT name FROM customer ORDER BY id")) {
            assertTrue(resultSet.next());
            assertEquals("张*", resultSet.getString(1));
            assertTrue(resultSet.next());
            assertEquals("欧**明", resultSet.getString(1));
        }
    }

    @Test
    void shouldMatchPlaintextPhoneAndMaskAliasedResult() throws SQLException {
        try (Connection connection = openGateway(); PreparedStatement statement = connection.prepareStatement(
                "SELECT phone AS contact_number FROM customer WHERE phone LIKE ? AND id = 1")) {
            statement.setString(1, "%0013%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("138****8000", resultSet.getString("contact_number"));
            }
        }
    }

    @Test
    void shouldMatchPlaintextAddressAndMaskSubqueryAlias() throws SQLException {
        try (Connection connection = openGateway(); PreparedStatement statement = connection.prepareStatement(
                "SELECT x.location FROM (SELECT id, address AS location FROM customer) x "
                        + "WHERE x.location LIKE ? AND x.id = 1")) {
            statement.setString(1, "%建国路%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("北京市朝阳区******", resultSet.getString("location"));
            }
        }
    }

    @Test
    void shouldMapAndMaskAllConfiguredSensitiveColumns() throws SQLException {
        try (Connection connection = openGateway(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id_card AS credential, name AS person, phone AS mobile, address AS location "
                             + "FROM customer WHERE id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("110101********1234", resultSet.getString("credential"));
            assertEquals("张*", resultSet.getString("person"));
            assertEquals("138****8000", resultSet.getString("mobile"));
            assertEquals("北京市朝阳区******", resultSet.getString("location"));
        }
    }

    @Test
    void shouldMatchPlaintextAndEncryptReturnedValueWithRandomNonce() throws SQLException {
        String first = queryEncryptedSecret();
        String second = queryEncryptedSecret();
        assertTrue(first.startsWith("GW1:output-key-v1:"));
        assertNotEquals(first, second);
        byte[] key = Base64.getDecoder().decode(requiredEnvironment("GATEWAY_OUTPUT_AES_KEY_B64"));
        AesGcmCodec codec = new AesGcmCodec(key, "output-key-v1", "gateway_mask_test.customer.secret_value");
        assertEquals("payload-one", codec.decrypt(first));
        assertEquals("payload-one", codec.decrypt(second));
    }

    private String queryEncryptedSecret() throws SQLException {
        try (Connection connection = openGateway(); PreparedStatement statement = connection.prepareStatement(
                "SELECT secret_value AS protected_value FROM customer WHERE secret_value LIKE ? AND id = 1")) {
            statement.setString(1, "%payload%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString("protected_value");
            }
        }
    }

    private void assertSingleValue(final String sql, final String expected) throws SQLException {
        try (Connection connection = openGateway(); Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getString(1));
        }
    }

    private Connection openGateway() throws SQLException {
        return DriverManager.getConnection(requiredEnvironment("GATEWAY_JDBC_URL"), requiredEnvironment("GATEWAY_USER"), requiredEnvironment("GATEWAY_PASSWORD"));
    }

    private String requiredEnvironment(final String name) {
        String result = System.getenv(name);
        if (null == result || result.trim().isEmpty()) {
            throw new IllegalStateException("Environment variable '" + name + "' is required.");
        }
        return result;
    }
}
