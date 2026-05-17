package ru.mngerasimenko.todolist.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Утилитный класс для работы с токенами (хеширование и генерация).
 */
public final class TokenUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenUtils() {
    }

    /**
     * SHA-256 хеш строки (для хранения токенов в БД).
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Криптостойкий random-токен в hex (для unsubscribe-link, refresh-token и т.п.).
     * 32 байта = 256 бит энтропии = 64 hex-символа.
     *
     * @param numBytes число случайных байт (рекомендация: 32 для секретных URL-токенов)
     */
    public static String secureRandomHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
