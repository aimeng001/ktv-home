package com.homektv.ai;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void encryptsDecryptsAndRejectsTamperingOrDifferentSecretNames() {
        Path keyPath = tempDir.resolve("generated/secrets/config.key");
        SecretCryptoService crypto = service(keyPath, tempDir.resolve("data"));

        SecretCryptoService.EncryptedValue encrypted = crypto.encrypt("ai.api-key", "local-test-secret");

        assertThat(crypto.decrypt("ai.api-key", encrypted.ciphertext(), encrypted.nonce()))
                .isEqualTo("local-test-secret");
        assertThat(Files.exists(keyPath)).isTrue();
        assertThatThrownBy(() -> crypto.decrypt("other.setting", encrypted.ciphertext(), encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感配置解密失败");

        byte[] tampered = encrypted.ciphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt("ai.api-key", tampered, encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感配置解密失败");
    }

    @Test
    void loadsExistingBase64MasterKeyAcrossServiceInstances() throws Exception {
        Path keyPath = tempDir.resolve("base64/secrets/config.key");
        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(new byte[32]));

        SecretCryptoService first = service(keyPath, tempDir.resolve("data"));
        SecretCryptoService.EncryptedValue encrypted = first.encrypt("ai.api-key", "fixture-key");
        SecretCryptoService restarted = service(keyPath, tempDir.resolve("data"));

        assertThat(restarted.decrypt("ai.api-key", encrypted.ciphertext(), encrypted.nonce()))
                .isEqualTo("fixture-key");
    }

    @Test
    void acceptsExistingHexMasterKey() throws Exception {
        Path keyPath = tempDir.resolve("hex/secrets/config.key");
        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, "0123456789abcdef".repeat(4));

        SecretCryptoService first = service(keyPath, tempDir.resolve("data"));
        SecretCryptoService.EncryptedValue encrypted = first.encrypt("provider", "fixture-value");

        assertThat(service(keyPath, tempDir.resolve("data"))
                .decrypt("provider", encrypted.ciphertext(), encrypted.nonce())).isEqualTo("fixture-value");
    }

    @Test
    void derivesStableKeyWhenHexSeedDoesNotContainThirtyTwoBytes() throws Exception {
        Path keyPath = tempDir.resolve("short-hex/secrets/config.key");
        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, "cafebabedeadbeef");

        SecretCryptoService first = service(keyPath, tempDir.resolve("data"));
        SecretCryptoService.EncryptedValue encrypted = first.encrypt("provider", "fixture-value");

        assertThat(service(keyPath, tempDir.resolve("data"))
                .decrypt("provider", encrypted.ciphertext(), encrypted.nonce())).isEqualTo("fixture-value");
    }

    @Test
    void derivesStableMasterKeyFromLegacyTextValue() throws Exception {
        Path keyPath = tempDir.resolve("legacy/secrets/config.key");
        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, "legacy passphrase that is neither base64 nor hex");

        SecretCryptoService first = service(keyPath, tempDir.resolve("data"));
        SecretCryptoService.EncryptedValue encrypted = first.encrypt("provider", "fixture-value");

        assertThat(service(keyPath, tempDir.resolve("data"))
                .decrypt("provider", encrypted.ciphertext(), encrypted.nonce())).isEqualTo("fixture-value");
    }

    @Test
    void fallsBackToApplicationDataWhenConfiguredKeyParentIsAFile() throws Exception {
        Path conflictingParent = tempDir.resolve("not-a-directory");
        Files.writeString(conflictingParent, "fixture");
        Path configuredKey = conflictingParent.resolve("secrets/config.key");
        Path dataPath = tempDir.resolve("application-data");

        SecretCryptoService.EncryptedValue encrypted = service(configuredKey, dataPath)
                .encrypt("provider", "fixture-value");

        assertThat(dataPath.resolve("secrets/config.key")).exists();
        assertThat(service(configuredKey, dataPath)
                .decrypt("provider", encrypted.ciphertext(), encrypted.nonce())).isEqualTo("fixture-value");
    }

    private SecretCryptoService service(Path keyPath, Path dataPath) {
        AppProperties properties = new AppProperties();
        properties.setConfigMasterKeyPath(keyPath.toString());
        properties.setDataPath(dataPath.toString());
        return new SecretCryptoService(properties);
    }
}
