package ru.mngerasimenko.todolist.util;

/**
 * Утилиты для безопасного логирования (маскировка PII).
 */
public final class LogUtils {

    private LogUtils() {
    }

    /**
     * Маскирует email для логов: "user@example.com" → "us***@example.com"
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return "null";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        int visibleChars = Math.min(2, atIndex);
        return email.substring(0, visibleChars) + "***" + email.substring(atIndex);
    }
}
