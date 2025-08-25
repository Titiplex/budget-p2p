package com.titiplex.budget.core.crypto;

import org.bouncycastle.crypto.generators.SCrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public final class CryptoBox {
    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] deriveKey(String passphrase, byte[] salt) {
        return SCrypt.generate(passphrase.getBytes(StandardCharsets.UTF_8), salt, 1 << 15, 8, 1, 32);
    }

    public static byte[] encrypt(String passphrase, byte[] plaintext) {
        try {
            byte[] salt = new byte[16];
            RNG.nextBytes(salt);
            byte[] key = deriveKey(passphrase, salt);
            byte[] nonce = new byte[12];
            RNG.nextBytes(nonce);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec sk = new SecretKeySpec(key, "AES");
            c.init(Cipher.ENCRYPT_MODE, sk, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plaintext);

            ByteBuffer bb = ByteBuffer.allocate(4 + salt.length + nonce.length + ct.length);
            bb.putInt(salt.length).put(salt).put(nonce).put(ct);
            return bb.array();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] decrypt(String passphrase, byte[] blob) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(blob);
            int saltLen = bb.getInt();
            byte[] salt = new byte[saltLen];
            bb.get(salt);
            byte[] nonce = new byte[12];
            bb.get(nonce);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);

            byte[] key = deriveKey(passphrase, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec sk = new SecretKeySpec(key, "AES");
            c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(128, nonce));
            return c.doFinal(ct);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}