package com.example.doris.gateway.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DorisTablePropertiesTest {
    @Test void preservesOffsetsAndIgnoresQuotedCommentContent() {
        String sql="/* PROPERTIES('x') */ CREATE TABLE zz PROPERTIES(\"replication_num\"=\"1\",'comment'='a) AS SELECT') AS SELECT id FROM aa";
        String view=DorisTableProperties.parserView(sql);
        assertEquals(sql.length(),view.length());
        assertTrue(view.startsWith("/* PROPERTIES('x') */ CREATE TABLE zz "));
        assertEquals(sql.indexOf("AS SELECT id"),view.indexOf("AS SELECT id"));
        assertFalse(view.contains("replication_num"));
    }
    @Test void doesNotTouchOrdinaryExpressionsOrTableNames() {
        String select="SELECT 'PROPERTIES(x)' FROM aa";assertEquals(select,DorisTableProperties.parserView(select));
        String create="CREATE TABLE properties (id INT)";assertEquals(create,DorisTableProperties.parserView(create));
    }
}
