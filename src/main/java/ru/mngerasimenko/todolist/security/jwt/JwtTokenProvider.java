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
import java.util.Date;

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

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Генерирует refresh токен для пользователя
     *
     * @param username имя пользователя
     * @return JWT refresh токен
     */
    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getRefreshTokenExpiration());

        return Jwts.builder()
                .subject(username)
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
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.getSubject();
    }

    /**
     * Валидирует JWT токен
     *
     * @param token JWT токен
     * @return true если токен валиден, false в противном случае
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SecurityException ex) {
            log.error("Неверная подпись JWT токена", ex);
        } catch (MalformedJwtException ex) {
            log.error("Некорректный JWT токен", ex);
        } catch (ExpiredJwtException ex) {
            log.error("Истёк срок действия JWT токена", ex);
        } catch (UnsupportedJwtException ex) {
            log.error("Неподдерживаемый JWT токен", ex);
        } catch (IllegalArgumentException ex) {
            log.error("JWT claims пустой", ex);
        }
        return false;
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
