package ru.mngerasimenko.todolist.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Конфигурация параметров JWT токенов.
 * Значения загружаются из application.properties с префиксом "jwt"
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * Секретный ключ для подписи JWT токенов.
     * Должен быть не менее 256 бит (32 символа).
     * В production должен храниться в переменной окружения JWT_SECRET
     */
    private String secret;

    /**
     * Время жизни access токена в миллисекундах.
     * По умолчанию: 3600000 (1 час)
     */
    private long accessTokenExpiration = 3600000L;

    /**
     * Время жизни refresh токена в миллисекундах.
     * По умолчанию: 604800000 (7 дней)
     */
    private long refreshTokenExpiration = 604800000L;

    /**
     * Флаг Secure для refresh-cookie веб-клиента.
     * true (по умолчанию) — cookie передаётся только по HTTPS (production).
     * Для локальной разработки/staging без TLS можно выключить через
     * JWT_REFRESH_COOKIE_SECURE=false (иначе браузер не примет cookie по http).
     */
    private boolean refreshCookieSecure = true;
}
