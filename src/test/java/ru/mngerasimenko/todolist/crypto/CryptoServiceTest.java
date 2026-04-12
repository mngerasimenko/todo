package ru.mngerasimenko.todolist.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты для CryptoService (AES-256-GCM).
 */
class CryptoServiceTest {

    /** Тестовый ключ 32 байта в Base64 */
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes()
    );

    private final CryptoService crypto = new CryptoService(TEST_KEY);

    // ===== encrypt / decrypt =====

    @Test
    void encryptDecrypt_RoundTrip() {
        String original = "test@example.com";
        String encrypted = crypto.encrypt(original);
        String decrypted = crypto.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encrypt_DifferentIV_EachTime() {
        String original = "same input";
        String encrypted1 = crypto.encrypt(original);
        String encrypted2 = crypto.encrypt(original);

        // Разный IV → разный шифротекст
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // Но оба расшифровываются одинаково
        assertThat(crypto.decrypt(encrypted1)).isEqualTo(original);
        assertThat(crypto.decrypt(encrypted2)).isEqualTo(original);
    }

    @Test
    void encrypt_Null_ReturnsNull() {
        assertThat(crypto.encrypt(null)).isNull();
    }

    @Test
    void decrypt_Null_ReturnsNull() {
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    void encrypt_CyrillicText_RoundTrip() {
        String original = "Список покупок 🛒";
        String encrypted = crypto.encrypt(original);
        String decrypted = crypto.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void decrypt_PlainText_ReturnsAsIs() {
        // Незашифрованный текст (не Base64 с AES) — возвращается как есть
        String plainText = "обычный текст";
        assertThat(crypto.decrypt(plainText)).isEqualTo(plainText);
    }

    // ===== blind index =====

    @Test
    void blindIndex_Deterministic() {
        String hash1 = crypto.blindIndex("test@example.com");
        String hash2 = crypto.blindIndex("test@example.com");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void blindIndex_CaseInsensitive() {
        // blindIndex нормализует в lowercase внутри
        String hash1 = crypto.blindIndex("test@example.com");
        String hash2 = crypto.blindIndex("TEST@EXAMPLE.COM");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void blindIndex_DifferentInputs_DifferentHashes() {
        String hash1 = crypto.blindIndex("user1@mail.ru");
        String hash2 = crypto.blindIndex("user2@mail.ru");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void blindIndex_Returns64CharHex() {
        String hash = crypto.blindIndex("test@example.com");

        // HMAC-SHA256 → 32 байта → 64 hex символа
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    void blindIndex_Null_ReturnsNull() {
        assertThat(crypto.blindIndex(null)).isNull();
    }

    // ===== disabled mode =====

    @Test
    void disabled_EncryptReturnsPlainText() {
        CryptoService disabled = new CryptoService("");

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.encrypt("secret")).isEqualTo("secret");
        assertThat(disabled.decrypt("secret")).isEqualTo("secret");
        assertThat(disabled.blindIndex("test@mail.ru")).isEqualTo("test@mail.ru");
    }

    // ===== invalid key =====

    @Test
    void invalidKeyLength_ThrowsException() {
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> new CryptoService(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 байта");
    }
}
