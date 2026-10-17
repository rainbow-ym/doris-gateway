package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlTextEnvelopeTest {

    @Test
    void shouldSkipClientEnvelopeCommentsAndBom() {
        String sql = "\uFEFF  /* ApplicationName=DBeaver */\n-- trace_id=1\n# source=crm\nCREATE TABLE t AS SELECT 1";
        assertEquals("CREATE TABLE t AS SELECT 1", SqlTextEnvelope.executableStatement(sql));
    }

    @Test
    void shouldSkipOptimizerHintOnlyForClassificationView() {
        assertEquals("CREATE TABLE t AS SELECT 1", SqlTextEnvelope.executableStatement("/*+ SET_VAR(query_timeout=10) */ CREATE TABLE t AS SELECT 1"));
    }

    @Test
    void shouldNotTreatCommentTokensInsideStatementAsEnvelope() {
        assertEquals("SELECT '/* not a comment */', '-- still text'", SqlTextEnvelope.executableStatement(" SELECT '/* not a comment */', '-- still text'"));
    }

    @Test
    void shouldRejectUnterminatedAndExecutableComments() {
        assertThrows(IllegalArgumentException.class, () -> SqlTextEnvelope.executableStatement("/* missing"));
        assertThrows(IllegalArgumentException.class, () -> SqlTextEnvelope.executableStatement("/*!40101 SET NAMES utf8 */"));
    }
}
