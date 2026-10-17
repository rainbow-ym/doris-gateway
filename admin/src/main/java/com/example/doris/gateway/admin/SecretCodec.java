package com.example.doris.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class SecretCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCodec(@Value("${gateway-admin.master-key}") final String configuredKey) {
        if (configuredKey == null || configuredKey.trim().isEmpty()) {
            throw new IllegalStateException("ADMIN_MASTER_KEY_B64 is required.");
        }
        byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("ADMIN_MASTER_KEY_B64 must decode to 32 bytes.");
        }
        key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(final String plainText) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to encrypt stored credential.", ex);
        }
    }

    public String decrypt(final String encoded) {
        try {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported encrypted credential format.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getUrlDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to decrypt stored credential.", ex);
        }
    }
}
