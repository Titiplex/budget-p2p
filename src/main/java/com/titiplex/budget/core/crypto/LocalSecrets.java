package com.titiplex.budget.core.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Component
public class LocalSecrets {
    private final Path dir;
    private final Path deviceKeyPath;
    private static final SecureRandom RNG = new SecureRandom();

    public LocalSecrets(@Value("${app.data.dir}") String dataDir) {
        this.dir = Paths.get(dataDir);
        this.deviceKeyPath = dir.resolve("device.key");
    }

    private byte[] key() {
        try {
            Files.createDirectories(dir);
            if (!Files.exists(deviceKeyPath)) {
                byte[] k = new byte[32];
                RNG.nextBytes(k);
                Files.write(deviceKeyPath, k, StandardOpenOption.CREATE_NEW);
                try {
                    // Meilleures permissions possibles (ignore si non-POSIX, ex: Windows)
                    Files.setPosixFilePermissions(deviceKeyPath, Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                }
                return k;
            }
            return Files.readAllBytes(deviceKeyPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Chiffre et renvoie base64(nonce(12) || ciphertext)
     */
    public String seal(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] blob = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, blob, 0, nonce.length);
            System.arraycopy(ct, 0, blob, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String open(String blobB64) {
        try {
            byte[] blob = Base64.getDecoder().decode(blobB64);
            byte[] nonce = new byte[12];
            byte[] ct = new byte[blob.length - 12];
            System.arraycopy(blob, 0, nonce, 0, 12);
            System.arraycopy(blob, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] pt = c.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
