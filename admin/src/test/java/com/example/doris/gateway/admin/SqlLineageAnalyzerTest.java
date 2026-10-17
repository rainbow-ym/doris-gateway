package com.example.doris.gateway.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlLineageAnalyzerTest {

    private SqlLineageAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AdminStore store = mock(AdminStore.class);
        when(store.tableColumns(anyLong(), anyString(), anyString())).thenReturn(Arrays.asList("证件号码", "姓名", "电话", "地址"));
        analyzer = new SqlLineageAnalyzer(store);
    }

    @Test
    void shouldRejectUnresolvedScalarSubqueryBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                "CREATE TABLE copied AS SELECT (SELECT 证件号码 FROM 人口关系网表 LIMIT 1) AS innocent", 1L, "DWH_入口"));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                "INSERT INTO copied (innocent) SELECT CONCAT('', (SELECT 证件号码 FROM 人口关系网表 LIMIT 1)) AS innocent", 1L, "DWH_入口"));
    }

    @Test
    void shouldResolveCtasSubqueryAndAliases() throws Exception {
        SqlLineageAnalyzer.Analysis result = analyzer.analyze(
                "CREATE TABLE 人口副本 AS SELECT x.证件号码 AS 身份标识, x.姓名 FROM (SELECT 证件号码, 姓名 FROM 人口关系网表) x", 1L, "DWH_入口");
        assertEquals("CTAS", result.statementType);
        assertEquals("人口副本", result.target.table);
        assertEquals(2, result.edges.size());
        assertEquals("证件号码", result.edges.get(0).source.column);
        assertEquals("身份标识", result.edges.get(0).targetColumn);
    }

    @Test
    void shouldResolveDbeaverCommentedCtas() throws Exception {
        SqlLineageAnalyzer.Analysis result = analyzer.analyze(
                "/* ApplicationName=DBeaver 26.2.2 */ CREATE TABLE 人口副本 AS SELECT 证件号码, 姓名 FROM 人口关系网表", 1L, "DWH_入口");
        assertEquals("CTAS", result.statementType);
        assertEquals("人口副本", result.target.table);
        assertEquals(2, result.edges.size());
    }

    @Test
    void shouldResolveExpressionAndInsertTargets() throws Exception {
        SqlLineageAnalyzer.Analysis result = analyzer.analyze(
                "INSERT INTO 输出表 (证件号码,姓名) SELECT p.证件号码, CONCAT(p.姓名, '') AS 姓名 FROM 人口关系网表 p", 1L, "DWH_入口");
        assertEquals("INSERT_SELECT", result.statementType);
        assertEquals(2, result.edges.size());
        assertEquals("EXPRESSION", result.edges.get(1).transformType);
    }

    @Test
    void shouldResolveRenameAndDrop() throws Exception {
        SqlLineageAnalyzer.Analysis rename = analyzer.analyze("ALTER TABLE 人口副本 RENAME TO 人口新表", 1L, "DWH_入口");
        assertEquals("RENAME", rename.statementType);
        assertEquals("人口新表", rename.renameTarget.table);
        SqlLineageAnalyzer.Analysis drop = analyzer.analyze("DROP TABLE IF EXISTS 人口新表", 1L, "DWH_入口");
        assertEquals("DROP", drop.statementType);
        assertEquals("人口新表", drop.target.table);
    }

    @Test
    void shouldResolveDorisAsyncMaterializedView() throws Exception {
        SqlLineageAnalyzer.Analysis result = analyzer.analyze(
                "CREATE MATERIALIZED VIEW 人口物化视图 BUILD IMMEDIATE REFRESH COMPLETE ON MANUAL "
                        + "DISTRIBUTED BY RANDOM BUCKETS 1 AS SELECT 证件号码 AS 身份标识 FROM 人口关系网表", 1L, "DWH_入口");
        assertEquals("CREATE_MATERIALIZED_VIEW", result.statementType);
        assertEquals("人口物化视图", result.target.table);
        assertEquals("身份标识", result.edges.get(0).targetColumn);
    }
}
