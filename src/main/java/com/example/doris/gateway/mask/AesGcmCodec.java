package com.example.doris.gateway.mask;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM envelope codec used by the output encryption mask algorithm.
 */
final class AesGcmCodec {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int NONCE_LENGTH_BYTES = 12;

    private static final int TAG_LENGTH_BITS = 128;

    private static final String ENVELOPE_PREFIX = "GW1";

    private final SecretKey secretKey;

    private final String keyId;

    private final byte[] aad;

    private final SecureRandom secureRandom;

    AesGcmCodec(final byte[] key, final String keyId, final String aad) {
        this(key, keyId, aad, new SecureRandom());
    }

    AesGcmCodec(final byte[] key, final String keyId, final String aad, final SecureRandom secureRandom) {
        validateKey(key);
        if (null == keyId || keyId.trim().isEmpty() || keyId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("key-id must be non-empty and must not contain ':'.");
        }
        secretKey = new SecretKeySpec(key.clone(), "AES");
        this.keyId = keyId;
        this.aad = null == aad ? new byte[0] : aad.getBytes(StandardCharsets.UTF_8);
        this.secureRandom = secureRandom;
    }

    private void validateKey(final byte[] key) {
        if (null == key || 16 != key.length && 24 != key.length && 32 != key.length) {
            throw new IllegalArgumentException("AES key must contain 16, 24 or 32 bytes.");
        }
    }

    String encrypt(final String plainText) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] ciphertextAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return ENVELOPE_PREFIX + ':' + keyId + ':' + encode(nonce) + ':' + encode(ciphertextAndTag);
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt the result value.", ex);
        }
    }

    String decrypt(final String envelope) {
        String[] parts = envelope.split(":", -1);
        if (4 != parts.length || !ENVELOPE_PREFIX.equals(parts[0]) || !keyId.equals(parts[1])) {
            throw new IllegalArgumentException("Unsupported ciphertext envelope.");
        }
        try {
            byte[] nonce = decode(parts[2]);
            if (NONCE_LENGTH_BYTES != nonce.length) {
                throw new IllegalArgumentException("Invalid AES-GCM nonce length.");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return new String(cipher.doFinal(decode(parts[3])), StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException ex) {
            throw new IllegalArgumentException("Ciphertext authentication failed.", ex);
        }
    }

    private String encode(final byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(final String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
