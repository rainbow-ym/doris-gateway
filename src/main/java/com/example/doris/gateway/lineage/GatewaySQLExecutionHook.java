package com.example.doris.gateway.lineage;

import org.apache.shardingsphere.database.connector.core.jdbcurl.parser.ConnectionProperties;
import org.apache.shardingsphere.infra.executor.sql.hook.SQLExecutionHook;
import org.apache.shardingsphere.infra.exception.kernel.metadata.TableNotFoundException;

import java.util.List;

/** Captures successful lineage-affecting SQL before Proxy sends success to the client. */
public final class GatewaySQLExecutionHook implements SQLExecutionHook {

    private final ThreadLocal<Execution> current = new ThreadLocal<>();

    @Override
    public void start(final String dataSourceName, final String sql, final List<Object> parameters,
                      final ConnectionProperties connectionProperties, final boolean isTrunkThread) {
        try {
            LineageEventEmitter.PreparedExecution prepared = LineageEventEmitter.prepareExecution(dataSourceName, sql);
            current.set(new Execution(dataSourceName, sql, prepared.executionId, prepared.statementType));
        } catch (final IllegalStateException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            String marker = "MANAGED_OBJECT_NOT_FOUND:";
            if (message.startsWith(marker)) {
                String value = message.substring(marker.length());
                int reason = value.indexOf(':');
                throw new TableNotFoundException(reason < 0 ? value : value.substring(0, reason));
            }
            throw ex;
        }
    }

    @Override
    public void finishSuccess() {
        Execution execution = current.get();
        current.remove();
        if (execution != null) {
            try { LineageEventEmitter.emitExecutedSql(execution.dataSourceName, execution.sql, execution.executionId, execution.statementType); }
            catch (RuntimeException error) { throw new com.example.doris.gateway.security.ExecutionConfirmation.PostExecutionFailure(execution.executionId,error); }
        }
    }

    @Override
    public void finishFailure(final Exception cause) {
        Execution execution = current.get();
        current.remove();
        if (execution != null) LineageEventEmitter.abortExecution(execution.executionId, cause);
    }

    private static final class Execution {
        private final String dataSourceName;
        private final String sql;
        private final String executionId;
        private final String statementType;

        private Execution(final String dataSourceName, final String sql, final String executionId, final String statementType) {
            this.dataSourceName = dataSourceName;
            this.sql = sql;
            this.executionId = executionId;
            this.statementType = statementType;
        }
    }
}
