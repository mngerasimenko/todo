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

    // ===== sign / verifySignature =====

    @Test
    void sign_Deterministic() {
        assertThat(crypto.sign("open:123")).isEqualTo(crypto.sign("open:123"));
    }

    @Test
    void sign_Returns16CharHex() {
        String sig = crypto.sign("open:123");

        // усечение HMAC-SHA256 до 16 hex-символов (64 бита)
        assertThat(sig).hasSize(16);
        assertThat(sig).matches("[0-9a-f]+");
    }

    @Test
    void sign_TypeAndValueBound_DifferentSignatures() {
        // разный тип события → разная подпись
        assertThat(crypto.sign("open:123")).isNotEqualTo(crypto.sign("click:123"));
        // разный userId → разная подпись
        assertThat(crypto.sign("open:123")).isNotEqualTo(crypto.sign("open:124"));
    }

    @Test
    void sign_CaseSensitive() {
        // в отличие от blindIndex, sign НЕ приводит вход к lowercase
        assertThat(crypto.sign("Open:123")).isNotEqualTo(crypto.sign("open:123"));
    }

    @Test
    void sign_Null_ReturnsNull() {
        assertThat(crypto.sign(null)).isNull();
    }

    @Test
    void verifySignature_ValidSignature_ReturnsTrue() {
        String sig = crypto.sign("open:123");

        assertThat(crypto.verifySignature("open:123", sig)).isTrue();
    }

    @Test
    void verifySignature_TamperedOrMismatched_ReturnsFalse() {
        String sig = crypto.sign("open:123");

        // подпись валидна для open:123, но проверяем против другого userId
        assertThat(crypto.verifySignature("open:124", sig)).isFalse();
        // случайный мусор вместо подписи
        assertThat(crypto.verifySignature("open:123", "deadbeefdeadbeef")).isFalse();
    }

    @Test
    void verifySignature_NullSignature_ReturnsFalse() {
        assertThat(crypto.verifySignature("open:123", null)).isFalse();
    }

    // ===== disabled mode =====

    @Test
    void disabled_EncryptReturnsPlainText() {
        CryptoService disabled = new CryptoService("");

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.encrypt("secret")).isEqualTo("secret");
        assertThat(disabled.decrypt("secret")).isEqualTo("secret");
        assertThat(disabled.blindIndex("test@mail.ru")).isEqualTo("test@mail.ru");
        // без ключа подпись недоступна: sign → null, verify → false
        assertThat(disabled.sign("open:1")).isNull();
        assertThat(disabled.verifySignature("open:1", "anything")).isFalse();
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
