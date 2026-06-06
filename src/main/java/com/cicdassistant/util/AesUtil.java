package com.cicdassistant.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AesUtil {

    private static final String ALGO = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private static byte[] keyBytes(String key) {
        byte[] base = key.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = i < base.length ? base[i] : 0;
        }
        return out;
    }

    public static String encrypt(String plain, String key) {
        if (plain == null) return null;
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(key), ALGO));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed", e);
        }
    }

    public static String decrypt(String cipherText, String key) {
        if (cipherText == null || cipherText.isEmpty()) return cipherText;
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes(key), ALGO));
            byte[] dec = c.doFinal(Base64.getDecoder().decode(cipherText));
            return new String(dec, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }
}
