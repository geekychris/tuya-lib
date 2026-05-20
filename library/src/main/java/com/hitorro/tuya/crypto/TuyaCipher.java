package com.hitorro.tuya.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES-128-ECB with PKCS5/PKCS7 padding — what Tuya v3.1 / v3.3 use on the wire.
 * The 16-byte key is the ASCII localKey for device traffic, or md5("yGAdlopoPVldABfn")
 * for UDP discovery on port 6667.
 */
public final class TuyaCipher {

    /** Tuya's well-known UDP discovery key salt; md5 of this gives the 16-byte AES key. */
    public static final String UDP_KEY_SALT = "yGAdlopoPVldABfn";

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private final SecretKeySpec keySpec;

    public TuyaCipher(byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes, got " + key.length);
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    public static TuyaCipher forLocalKey(String localKey) {
        return new TuyaCipher(localKey.getBytes(StandardCharsets.US_ASCII));
    }

    public static TuyaCipher forUdpDiscovery() {
        return new TuyaCipher(md5(UDP_KEY_SALT.getBytes(StandardCharsets.US_ASCII)));
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES decrypt failed", e);
        }
    }

    /** AES-128-GCM encrypt for v3.5. Returns ciphertext || 16-byte tag. */
    public byte[] gcmEncrypt(byte[] plaintext, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** AES-128-GCM decrypt for v3.5. {@code ciphertextWithTag} is ct || 16-byte tag. */
    public byte[] gcmDecrypt(byte[] ciphertextWithTag, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad);
            return cipher.doFinal(ciphertextWithTag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    public static byte[] randomIv(int length) {
        byte[] iv = new byte[length];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
