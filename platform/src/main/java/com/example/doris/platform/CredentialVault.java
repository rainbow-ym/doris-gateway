package com.example.doris.platform;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public final class CredentialVault {
    private final SecretKeySpec key;
    public CredentialVault(@Value("${PLATFORM_MASTER_KEY_B64}") String encoded) {
        byte[] raw=Base64.getDecoder().decode(encoded);
        if(raw.length!=32)throw new IllegalStateException("平台主密钥必须为 32 字节。");
        key=new SecretKeySpec(raw,"AES");
    }
    public String encrypt(String value) {
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException("凭据保存失败。");}
    }
    public String decrypt(String value) {
        try {
            String[] p=value.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("凭据读取失败，请核对平台主密钥。");}
    }
}
