package com.example.doris.gateway.admin;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Provider;
import java.util.*;

/** Versioned authenticated envelope; never falls back to unauthenticated modes. */
public final class Sm4ExportCodec {
    public static final String PREFIX="__GW_SM4_V1__:";
    private static final Provider PROVIDER=new BouncyCastleProvider();
    private static final SecureRandom RANDOM=new SecureRandom();
    public static String encrypt(byte[] plain,String keyId,byte[] key)throws Exception {
        validate(keyId,key);
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("SM4/GCM/NoPadding",PROVIDER);
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"SM4"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD((PREFIX+keyId).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext=cipher.doFinal(plain),packet=new byte[nonce.length+ciphertext.length];
        System.arraycopy(nonce,0,packet,0,nonce.length);System.arraycopy(ciphertext,0,packet,nonce.length,ciphertext.length);
        return PREFIX+keyId+":"+Base64.getEncoder().encodeToString(packet);
    }
    public static String keyId(String envelope){
        if(envelope==null||!envelope.startsWith(PREFIX)||envelope.length()>30000)throw new IllegalArgumentException("密文格式或长度不正确。");
        int end=envelope.indexOf(':',PREFIX.length());
        if(end<0)throw new IllegalArgumentException("密文格式不正确。");
        String id=envelope.substring(PREFIX.length(),end);
        if(!id.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException("密钥版本不正确。");
        return id;
    }
    public static byte[] decrypt(String envelope,byte[] key)throws Exception {
        String id=keyId(envelope);validate(id,key);
        byte[] packet=Base64.getDecoder().decode(envelope.substring(PREFIX.length()+id.length()+1));
        if(packet.length<28)throw new IllegalArgumentException("密文已截断。");
        Cipher cipher=Cipher.getInstance("SM4/GCM/NoPadding",PROVIDER);
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"SM4"),new GCMParameterSpec(128,Arrays.copyOf(packet,12)));
        cipher.updateAAD((PREFIX+id).getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(packet,12,packet.length-12);
    }
    private static void validate(String id,byte[] key){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||key==null||key.length!=16)throw new IllegalArgumentException("SM4 密钥必须为 16 字节，密钥版本必须有效。");
    }
}
