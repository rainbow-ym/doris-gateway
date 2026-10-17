package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_GATEWAY_INTEGRATION", matches = "(?i)true")
class GatewayLineageE2ETest {

    @Test
    void shouldInheritMaskForCtasAliasRenameAndDrop() throws Exception {
        prepareDatabaseDefaults();
        dropDirect("DROP TABLE IF EXISTS customer_lineage_renamed");
        dropDirect("DROP TABLE IF EXISTS customer_lineage");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer_lineage AS SELECT id, id_card AS credential, name FROM customer");
            assertMasked(statement, "SELECT credential FROM customer_lineage WHERE id=1", "110101********1234");
            statement.execute("ALTER TABLE customer_lineage RENAME customer_lineage_renamed");
            assertMasked(statement, "SELECT credential FROM customer_lineage_renamed WHERE id=1", "110101********1234");
            statement.execute("DROP TABLE customer_lineage_renamed");
        }
    }

    @Test
    void shouldInheritMaskForDbeaverCommentedUnicodeCtas() throws Exception {
        prepareDatabaseDefaults();
        dropDirect("DROP TABLE IF EXISTS `人口关系网_注释测试`");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("/* ApplicationName=DBeaver 26.2.2 - SQLEditor <Console> */ "
                    + "CREATE TABLE `人口关系网_注释测试` AS "
                    + "SELECT id, id_card AS `证件号码`, name AS `姓名` FROM customer");
            assertMasked(statement, "SELECT `证件号码` FROM `人口关系网_注释测试` WHERE id=1", "110101********1234");
            statement.execute("DROP TABLE `人口关系网_注释测试`");
        }
    }

    @Test
    void shouldInheritMaskForInsertSelect() throws Exception {
        prepareDatabaseDefaults();
        dropDirect("DROP TABLE IF EXISTS customer_insert_lineage");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer_insert_lineage (id BIGINT, credential VARCHAR(64))");
            statement.execute("INSERT INTO customer_insert_lineage (id, credential) SELECT id, id_card FROM customer");
            assertMasked(statement, "SELECT credential FROM customer_insert_lineage WHERE id=1", "110101********1234");
            statement.execute("DROP TABLE customer_insert_lineage");
        }
    }

    @Test
    void shouldInheritMaskForView() throws Exception {
        dropDirect("DROP VIEW IF EXISTS customer_view_lineage");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE VIEW customer_view_lineage AS SELECT id, id_card AS credential FROM customer");
            assertMasked(statement, "SELECT credential FROM customer_view_lineage WHERE id=1", "110101********1234");
            statement.execute("DROP VIEW customer_view_lineage");
        }
    }

    @Test
    void shouldCaptureMaterializedViewLineage() throws Exception {
        prepareDatabaseDefaults();
        dropDirect("DROP MATERIALIZED VIEW IF EXISTS customer_mv_lineage");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE MATERIALIZED VIEW customer_mv_lineage BUILD IMMEDIATE REFRESH COMPLETE ON MANUAL "
                    + "DISTRIBUTED BY RANDOM BUCKETS 1 AS SELECT id, id_card AS credential FROM customer");
            statement.execute("DROP MATERIALIZED VIEW customer_mv_lineage");
        }
    }

    private static void assertMasked(final Statement statement, final String sql, final String expected) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getString(1));
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(System.getenv("GATEWAY_JDBC_URL"), System.getenv("GATEWAY_USER"), System.getenv("GATEWAY_PASSWORD"));
    }

    private static void prepareDatabaseDefaults() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("DORIS_JDBC_URL"), System.getenv("DORIS_USER"), System.getenv("DORIS_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE gateway_mask_test SET PROPERTIES ('replication_allocation'='tag.location.default:1')");
        }
    }

    private static void dropDirect(final String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("DORIS_JDBC_URL"), System.getenv("DORIS_USER"), System.getenv("DORIS_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        String table = sql.trim().substring(sql.trim().lastIndexOf(' ') + 1).replace("`", "");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            try {
                statement.execute("REFRESH TABLE METADATA " + table);
            } catch (final java.sql.SQLException ignored) {
                // Refresh removes the stale Proxy entry and may report the now-missing table.
            }
        }
    }
}
