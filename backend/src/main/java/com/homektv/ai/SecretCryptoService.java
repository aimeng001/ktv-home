package com.homektv.ai;

import com.homektv.config.AppProperties;
import com.homektv.library.LibraryModePolicy;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Service
public class SecretCryptoService {
    private static final int GCM_TAG_BITS = 128;
    private final AppProperties properties;
    private volatile byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(AppProperties properties) {
        this.properties = properties;
    }

    public EncryptedValue encrypt(String name, String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (Exception e) {
            throw new IllegalStateException("敏感配置加密失败", e);
        }
    }

    public String decrypt(String name, byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感配置解密失败，请检查 KTV_CONFIG_MASTER_KEY", e);
        }
    }

    private byte[] loadMasterKey(AppProperties properties) {
        String environment = System.getenv("KTV_CONFIG_MASTER_KEY");
        if (environment != null && !environment.isBlank()) return normalizeKey(environment.trim());
        Path keyPath = Path.of(properties.getConfigMasterKeyPath()).toAbsolutePath().normalize();
        LibraryModePolicy.requireCacheOutsideExternalSource(properties, keyPath);
        try {
            if (Files.exists(keyPath)) return normalizeKey(Files.readString(keyPath).trim());
            try {
                Files.createDirectories(keyPath.getParent());
            } catch (Exception unavailable) {
                // Local development may not be allowed to create /data; retain the
                // same permissions policy under the configured application data path.
                keyPath = Path.of(properties.getDataPath(), "secrets", "config.key").toAbsolutePath().normalize();
                LibraryModePolicy.requireCacheOutsideExternalSource(properties, keyPath);
                Files.createDirectories(keyPath.getParent());
            }
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);
            try {
                Files.writeString(keyPath, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                return normalizeKey(Files.readString(keyPath).trim());
            }
            try {
                Files.setPosixFilePermissions(keyPath, Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows/non-POSIX filesystems do not expose POSIX permissions.
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取或生成配置主密钥：" + keyPath, e);
        }
    }

    private byte[] masterKey() {
        byte[] current = masterKey;
        if (current != null) return current;
        synchronized (this) {
            if (masterKey == null) masterKey = loadMasterKey(properties);
            return masterKey;
        }
    }

    private byte[] normalizeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) { }
        try {
            byte[] decoded = HexFormat.of().parseHex(value);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) { }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化配置主密钥", e);
        }
    }

    public record EncryptedValue(byte[] ciphertext, byte[] nonce) { }
}
