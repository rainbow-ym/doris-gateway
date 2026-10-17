package com.example.doris.gateway.mask;

import org.apache.shardingsphere.mask.spi.MaskAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskAlgorithmServiceLoaderTest {

    @Test
    void shouldRegisterAllAlgorithmsThroughSpi() {
        Set<String> types = new HashSet<>();
        for (MaskAlgorithm<?, ?> each : ServiceLoader.load(MaskAlgorithm.class)) {
            types.add(each.getType());
        }
        assertTrue(types.contains("UNICODE_KEEP_FIRST_N_LAST_M"));
        assertTrue(types.contains("LENGTH_BASED_MASK"));
        assertTrue(types.contains("OUTPUT_AES_GCM"));
    }
}
