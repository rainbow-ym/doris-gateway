package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LengthBasedMaskAlgorithmTest {

    @Test
    void shouldMaskNamesByLength() {
        LengthBasedMaskAlgorithm algorithm = createAlgorithm("1=0,0;2=1,0;3=1,1;*=1,1");
        assertEquals("*", algorithm.mask("张"));
        assertEquals("张*", algorithm.mask("张三"));
        assertEquals("张*三", algorithm.mask("张小三"));
        assertEquals("欧**明", algorithm.mask("欧阳小明"));
        assertEquals("", algorithm.mask(""));
        assertNull(algorithm.mask(null));
    }

    @Test
    void shouldTreatSupplementaryCharactersAsOneCharacter() {
        LengthBasedMaskAlgorithm algorithm = createAlgorithm("2=1,0;*=0,0");
        assertEquals("𠀀*", algorithm.mask("𠀀张"));
    }

    @Test
    void shouldRejectMissingDefaultRule() {
        LengthBasedMaskAlgorithm algorithm = new LengthBasedMaskAlgorithm();
        Properties props = new Properties();
        props.setProperty("rules", "2=1,0");
        assertThrows(IllegalArgumentException.class, () -> algorithm.init(props));
    }

    private LengthBasedMaskAlgorithm createAlgorithm(final String rules) {
        LengthBasedMaskAlgorithm result = new LengthBasedMaskAlgorithm();
        Properties props = new Properties();
        props.setProperty("rules", rules);
        props.setProperty("replace-char", "*");
        result.init(props);
        return result;
    }
}
