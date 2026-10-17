package com.example.doris.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Service
public final class LineageIngressService {

    private final AdminStore store;
    private final ManagedSqlExecutionService managedSql;
    private final byte[] expectedToken;

    public LineageIngressService(final AdminStore store, final ManagedSqlExecutionService managedSql,
                                 @Value("${gateway-admin.lineage-token}") final String token) {
        if (token == null || token.length() < 24) {
            throw new IllegalStateException("LINEAGE_INGRESS_TOKEN must contain at least 24 characters.");
        }
        this.store = store;
        this.managedSql = managedSql;
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean accept(final String token, final Map<String, Object> event) {
        byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new SecurityException("Invalid lineage ingress token.");
        }
        if (event.containsKey("rawSql")) return managedSql.commit(token, required(event, "executionId"), event);
        return store.saveLineageEvent(event);
    }

    private static String required(final Map<String, Object> values, final String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) throw new IllegalArgumentException(key + " is required.");
        return String.valueOf(value).trim();
    }
}
