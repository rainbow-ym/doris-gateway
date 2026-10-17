package com.example.doris.gateway.security;

import java.sql.SQLException;
import org.apache.shardingsphere.sql.parser.statement.core.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.ddl.DDLStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.DMLStatement;
import org.apache.shardingsphere.sql.parser.statement.core.statement.type.dml.SelectStatement;

/** Until commit-aware lineage exists, never acknowledge transactional mutations as committed. */
public final class TransactionBoundary {
    private TransactionBoundary() { }

    public static void validate(SQLStatement statement, boolean autoCommit, boolean inTransaction) throws SQLException {
        boolean mutation = statement instanceof DDLStatement
                || statement instanceof DMLStatement && !(statement instanceof SelectStatement);
        if (mutation && (!autoCommit || inTransaction)) {
            throw new SQLException("当前网关尚不支持显式事务中的写入或结构变更，语句未下发 Doris；请结束事务并开启自动提交后重试。", "0A000");
        }
    }
}
