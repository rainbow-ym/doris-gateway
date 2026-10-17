package com.example.doris.gateway.mask;

import org.apache.shardingsphere.mask.spi.MaskAlgorithm;

import java.util.Base64;
import java.util.Properties;

/**
 * Replaces a returned value with an authenticated AES-GCM ciphertext envelope.
 *
 * <p>The key is loaded from an environment variable. The variable must contain
 * a Base64-encoded 128, 192 or 256-bit AES key. Production deployments should
 * inject this value from a secrets manager or replace this resolver with a
 * KMS/HSM-backed data-key provider.</p>
 */
public final class OutputAesGcmMaskAlgorithm implements MaskAlgorithm<Object, String> {

    private static final String KEY_ENV = "key-env";

    private static final String KEY_ID = "key-id";

    private static final String AAD = "aad";

    private AesGcmCodec codec;

    @Override
    public void init(final Properties props) {
        String keyEnvironmentVariable = requiredProperty(props, KEY_ENV);
        String encodedKey = System.getenv(keyEnvironmentVariable);
        if (null == encodedKey || encodedKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Environment variable '" + keyEnvironmentVariable + "' is missing or empty.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey.trim());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("Environment variable '" + keyEnvironmentVariable + "' must contain a Base64-encoded AES key.", ex);
        }
        codec = new AesGcmCodec(key, requiredProperty(props, KEY_ID), props.getProperty(AAD, ""));
    }

    private String requiredProperty(final Properties props, final String name) {
        String result = props.getProperty(name);
        if (null == result || result.trim().isEmpty()) {
            throw new IllegalArgumentException("Property '" + name + "' must not be empty.");
        }
        return result.trim();
    }

    @Override
    public String mask(final Object plainValue) {
        return null == plainValue ? null : codec.encrypt(String.valueOf(plainValue));
    }

    @Override
    public String getType() {
        return "OUTPUT_AES_GCM";
    }
}
