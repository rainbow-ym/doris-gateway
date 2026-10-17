package org.apache.shardingsphere.infra.metadata.database;

import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardingSphereDatabaseCaseCompatibilityTest {

    @Test
    void shouldMergeCaseOnlyDuplicateSchemasAndPreferDatabaseNameCasing() {
        ShardingSphereSchema lowercase = new ShardingSphereSchema("dwd_人口", null);
        ShardingSphereSchema canonical = new ShardingSphereSchema("DWD_人口", null);

        ShardingSphereDatabase actual = new ShardingSphereDatabase(
                "DWD_人口", null, null, null, Arrays.asList(lowercase, canonical));

        assertEquals(1, actual.getAllSchemas().size());
        assertEquals("DWD_人口", actual.getSchema("dwd_人口").getName());
        assertTrue(actual.containsSchema("DWD_人口"));
    }
}
