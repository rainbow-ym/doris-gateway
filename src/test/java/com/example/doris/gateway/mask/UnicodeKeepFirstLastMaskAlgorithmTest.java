package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnicodeKeepFirstLastMaskAlgorithmTest {

    @Test
    void shouldMaskCredentialNumber() {
        UnicodeKeepFirstLastMaskAlgorithm algorithm = createAlgorithm(6, 4);
        assertEquals("110101********1234", algorithm.mask("110101199001011234"));
        assertEquals("**********", algorithm.mask("1234567890"));
        assertEquals("", algorithm.mask(""));
        assertNull(algorithm.mask(null));
    }

    @Test
    void shouldFullyMaskShortPhoneAddressAndUnicodeValues() {
        assertEquals("*****", createAlgorithm(3, 4).mask("12345"));
        assertEquals("**", createAlgorithm(6, 0).mask("北京"));
        assertEquals("**", createAlgorithm(6, 0).mask("𠀀张"));
        assertEquals("***", createAlgorithm(Integer.MAX_VALUE, 1).mask("123"));
    }

    private UnicodeKeepFirstLastMaskAlgorithm createAlgorithm(final int first, final int last) {
        UnicodeKeepFirstLastMaskAlgorithm result = new UnicodeKeepFirstLastMaskAlgorithm();
        Properties props = new Properties();
        props.setProperty("first-n", String.valueOf(first));
        props.setProperty("last-m", String.valueOf(last));
        props.setProperty("replace-char", "*");
        result.init(props);
        return result;
    }
}
