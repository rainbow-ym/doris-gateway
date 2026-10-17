package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedSqlStatementTypeTest {

    @Test
    void shouldRecognizeDbeaverCommentedUnicodeCtas() {
        String sql = "/* ApplicationName=DBeaver 26.2.2 - SQLEditor <Console> */ "
                + "CREATE TABLE 人口关系网_NEW AS SELECT `姓名`,`证件号码` FROM DWD_人口.`人口关系网`";
        assertEquals("CTAS", ManagedSqlExecutionService.statementType(sql));
    }

    @Test
    void shouldRecognizeStackedCommentsAndOrdinaryCreate() {
        assertEquals("CTAS", ManagedSqlExecutionService.statementType("-- trace\n# client\nCREATE TABLE t AS SELECT id FROM source"));
        assertEquals("CREATE_TABLE", ManagedSqlExecutionService.statementType("/* client */ CREATE TABLE t (id BIGINT)"));
    }

    @Test
    void shouldNotClassifyKeywordsInsideStringsAsDdl() {
        assertEquals("QUERY", ManagedSqlExecutionService.statementType("SELECT 'CREATE TABLE t AS SELECT 1'"));
    }

    @Test
    void metadataTextMustNotBypassBusinessSql() {
        assertEquals("QUERY", ManagedSqlExecutionService.statementType("SELECT 'information_schema.' FROM private_table"));
        assertEquals("CTAS", ManagedSqlExecutionService.statementType("CREATE TABLE copied AS SELECT secret FROM src WHERE 'information_schema.' <> ''"));
        assertEquals("QUERY", ManagedSqlExecutionService.statementType("SELECT t.secret FROM src t JOIN information_schema.tables m ON 1=1"));
        assertEquals("QUERY", ManagedSqlExecutionService.statementType("SELECT TABLE_NAME FROM information_schema.tables"));
        assertEquals("BYPASS", ManagedSqlExecutionService.statementType("SHOW TABLES"));
    }
}
