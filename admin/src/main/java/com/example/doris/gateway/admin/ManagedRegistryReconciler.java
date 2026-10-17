package com.example.doris.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Retries only post-execution registry work; all failures remain fail-closed. */
@Component
public final class ManagedRegistryReconciler {

    private final AdminStore store;
    private final ManagedSqlExecutionService executions;
    private final Duration minimumAge;

    public ManagedRegistryReconciler(final AdminStore store, final ManagedSqlExecutionService executions,
                                     @Value("${gateway-admin.reconcile-minimum-age-seconds:600}") final long minimumAgeSeconds) {
        this.store = store;
        this.executions = executions;
        minimumAge = Duration.ofSeconds(Math.max(60L, minimumAgeSeconds));
    }

    @Scheduled(fixedDelayString = "${gateway-admin.reconcile-interval-ms:60000}")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(minimumAge);
        for (Map<String, Object> execution : store.pendingManagedExecutions()) {
            String updated = String.valueOf(value(execution, "updated_at"));
            try {
                if (Instant.parse(updated).isAfter(cutoff)) continue;
                String executionId = String.valueOf(value(execution, "execution_id"));
                executions.reconcile(executionId);
                store.audit("MANAGED_EXECUTION_RECONCILED", "executionId=" + executionId);
            } catch (final Exception ex) {
                store.audit("MANAGED_EXECUTION_RECONCILE_FAILED", "executionId=" + value(execution, "execution_id") + ",message=" + ex.getMessage());
            }
        }
    }

    private static Object value(final Map<String, Object> row, final String key) {
        if (row.containsKey(key)) return row.get(key);
        if (row.containsKey(key.toUpperCase())) return row.get(key.toUpperCase());
        return row.get(key.toLowerCase());
    }
}
