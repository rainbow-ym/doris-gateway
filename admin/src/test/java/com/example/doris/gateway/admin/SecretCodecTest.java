package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCodecTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptsAndDecryptsCredential() {
        SecretCodec codec = new SecretCodec(KEY);
        String encrypted = codec.encrypt("Doris密码-1");

        assertThat(encrypted).startsWith("v1:").doesNotContain("Doris密码-1");
        assertThat(codec.decrypt(encrypted)).isEqualTo("Doris密码-1");
    }

    @Test
    void rejectsInvalidMasterKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new SecretCodec(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
