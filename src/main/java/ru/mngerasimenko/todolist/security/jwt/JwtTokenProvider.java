package ru.mngerasimenko.todolist.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;

/**
 * Провайдер для генерации и валидации JWT токенов.
 * Использует библиотеку JJWT для работы с токенами.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    /**
     * Генерирует access токен для аутентифицированного пользователя
     *
     * @param authentication объект аутентификации Spring Security
     * @return JWT access токен
     */
    public String generateAccessToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return generateAccessToken(userDetails.getUsername());
    }

    /**
     * Генерирует access токен для пользователя по username
     *
     * @param username имя пользователя
     * @return JWT access токен
     */
    public String generateAccessToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());

        log.debug("Выдача access токена: user={}, истекает через {}с", maskEmail(username), jwtProperties.getAccessTokenExpiration() / 1000);

        return Jwts.builder()
                .subject(username)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Извлекает username из JWT токена
     *
     * @param token JWT токен
     * @return username пользователя
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Валидирует JWT токен (без проверки типа)
     *
     * @param token JWT токен
     * @return true если токен валиден, false в противном случае
     */
    public boolean validateToken(String token) {
        return parseClaims(token) != null;
    }

    /**
     * Валидирует access токен за один парсинг (подпись + срок + type=access)
     */
    public boolean validateAccessToken(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return false;
        }
        String type = claims.get("type", String.class);
        if (!"access".equals(type)) {
            log.warn("Токен не является access-токеном, type={}", type);
            return false;
        }
        return true;
    }

    /**
     * Извлекает время истечения токена
     */
    public Instant getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /**
     * Парсит и валидирует JWT, возвращает claims или null при ошибке.
     * Единственная точка парсинга — исключает повторную верификацию подписи.
     */
    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (SecurityException ex) {
            log.warn("Неверная подпись JWT токена");
        } catch (MalformedJwtException ex) {
            log.warn("Некорректный JWT токен");
        } catch (ExpiredJwtException ex) {
            log.warn("JWT токен истёк: user={}", maskEmail(ex.getClaims().getSubject()));
        } catch (UnsupportedJwtException ex) {
            log.warn("Неподдерживаемый JWT токен");
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims пустой");
        }
        return null;
    }

    /**
     * Получает ключ для подписи токенов
     *
     * @return SecretKey для HMAC-SHA алгоритма
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
