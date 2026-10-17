package com.example.doris.gateway.mask;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmCodecTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @Test
    void shouldRoundTripAndUseRandomNonce() {
        AesGcmCodec codec = new AesGcmCodec(KEY, "output-key-v1", "business_db.customer.id_card");
        String first = codec.encrypt("110101199001011234");
        String second = codec.encrypt("110101199001011234");
        assertTrue(first.startsWith("GW1:output-key-v1:"));
        assertNotEquals(first, second);
        assertEquals("110101199001011234", codec.decrypt(first));
        assertEquals("110101199001011234", codec.decrypt(second));
    }

    @Test
    void shouldRejectTamperedCiphertext() {
        AesGcmCodec codec = new AesGcmCodec(KEY, "output-key-v1", "business_db.customer.id_card");
        String encrypted = codec.encrypt("secret");
        String[] envelopeParts = encrypted.split(":", -1);
        byte[] ciphertextAndTag = Base64.getUrlDecoder().decode(envelopeParts[3]);
        ciphertextAndTag[0] ^= 1;
        envelopeParts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertextAndTag);
        String tampered = String.join(":", envelopeParts);
        assertThrows(IllegalArgumentException.class, () -> codec.decrypt(tampered));
    }

    @Test
    void shouldRejectInvalidKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCodec(Arrays.copyOf(KEY, 15), "key", "aad"));
    }
}
