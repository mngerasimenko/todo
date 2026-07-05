package ru.mngerasimenko.todolist.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Сервис шифрования персональных данных через AES-256-GCM.
 * Аппаратно ускорен (AES-NI на серверных CPU), минимальная нагрузка.
 *
 * Формат зашифрованного значения: Base64(IV[12] + ciphertext + authTag[16])
 * Ключ читается из переменной окружения ENCRYPTION_KEY (Base64, 32 байта).
 */
@Slf4j
@Service
public class CryptoService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // бит
    // Truncated hex signature length (16 hex = 8 bytes = 64 bits) — URL-friendly, enough against low-risk forgery
    private static final int SIGNATURE_HEX_LENGTH = 16;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CryptoService(@Value("${app.encryption-key:}") String encryptionKeyBase64) {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            log.warn("ENCRYPTION_KEY не задан — шифрование отключено, данные хранятся в открытом виде");
            this.secretKey = null;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("ENCRYPTION_KEY должен быть 32 байта (256 бит), получено: " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("CryptoService инициализирован (AES-256-GCM)");
        }
    }

    /** Проверить включено ли шифрование */
    public boolean isEnabled() {
        return secretKey != null;
    }

    /**
     * Зашифровать строку. Возвращает Base64(IV + ciphertext + tag).
     * Каждый вызов генерирует уникальный IV — одинаковый plaintext даёт разный результат.
     * Если шифрование отключено — возвращает исходную строку.
     */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        if (secretKey == null) return plainText;

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + encrypted (включает auth tag)
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            log.error("Ошибка шифрования", e);
            throw new RuntimeException("Ошибка шифрования данных", e);
        }
    }

    /**
     * Расшифровать строку. Принимает Base64(IV + ciphertext + tag).
     * Если шифрование отключено — возвращает исходную строку.
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        if (secretKey == null) return cipherText;

        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Не Base64 — значит незашифрованные данные (миграция ещё не прошла)
            return cipherText;
        } catch (Exception e) {
            // Ошибка расшифровки — возможно данные ещё не зашифрованы
            log.debug("Не удалось расшифровать, возвращаем как есть: {}", e.getMessage());
            return cipherText;
        }
    }

    /**
     * Blind index для поиска по зашифрованным полям (HMAC-SHA256).
     * Детерминированный — одинаковый input даёт одинаковый hash.
     * Используется для WHERE-запросов и unique constraint по email.
     */
    public String blindIndex(String value) {
        if (value == null) return null;
        if (secretKey == null) return value;

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(secretKey);
            byte[] hash = mac.doFinal(value.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            log.error("Ошибка вычисления blind index", e);
            throw new RuntimeException("Ошибка вычисления blind index", e);
        }
    }

    /**
     * HMAC-SHA256 подпись для защиты публичных ссылок email-трекинга от подделки.
     * Отдельный метод от {@link #blindIndex}: НЕ приводит вход к lowercase (подпись
     * чувствительна к регистру) и усечён до 16 hex-символов (URL-friendly, 64 бита —
     * достаточно против подделки низкорисковых метрик). Ключ — тот же ENCRYPTION_KEY;
     * отдельная деривация ключа для подписи отложена (см. blindIndex key-separation audit).
     *
     * @return усечённая hex-подпись, либо {@code null} если шифрование выключено
     */
    public String sign(String data) {
        if (data == null) return null;
        if (secretKey == null) return null;

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, SIGNATURE_HEX_LENGTH);
        } catch (Exception e) {
            log.error("Ошибка вычисления подписи", e);
            throw new RuntimeException("Ошибка вычисления подписи", e);
        }
    }

    /**
     * Проверить подпись в constant-time (защита от timing-атак через {@link MessageDigest#isEqual}).
     * Возвращает {@code false}, если подпись отсутствует, шифрование выключено или подпись не совпала.
     */
    public boolean verifySignature(String data, String signature) {
        if (signature == null || secretKey == null) return false;
        String expected = sign(data);
        if (expected == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
