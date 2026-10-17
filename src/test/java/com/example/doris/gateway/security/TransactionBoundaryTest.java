package com.example.doris.gateway.security;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.InsertStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.SelectStatement;
import static org.junit.jupiter.api.Assertions.*;

class TransactionBoundaryTest {
    @Test void ddlCannotImplicitlyCommitBeforeLineageAcknowledgement() {
        assertThrows(SQLException.class, () -> TransactionBoundary.validate(
                new org.apache.shardingsphere.sql.parser.statement.core.statement.type.ddl.table.CreateTableStatement(null), true, true));
    }
    @Test void rejectsBothExplicitBeginAndDisabledAutocommit() {
        InsertStatement insert = new InsertStatement(null);
        assertEquals("0A000", assertThrows(SQLException.class, () -> TransactionBoundary.validate(insert, true, true)).getSQLState());
        assertThrows(SQLException.class, () -> TransactionBoundary.validate(insert, false, false));
        assertThrows(SQLException.class, () -> TransactionBoundary.validate(insert, false, true));
        assertDoesNotThrow(() -> TransactionBoundary.validate(insert, true, false));
    }
    @Test void ordinarySelectRemainsAllowed() {
        assertDoesNotThrow(() -> TransactionBoundary.validate(new SelectStatement(null), false, true));
    }
}
