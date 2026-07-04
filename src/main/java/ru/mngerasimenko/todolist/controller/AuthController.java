package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.dto.auth.*;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.RefreshTokenService;
import ru.mngerasimenko.todolist.service.RefreshTokenService.RefreshTokenRotationResult;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.service.UserService;
import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;

/**
 * REST контроллер для аутентификации и регистрации пользователей
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Имя HttpOnly-cookie с refresh-токеном (веб-клиент, #259).
     * Path ограничен /api/auth — cookie не уходит на бизнес-эндпоинты.
     */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    /**
     * Вход пользователя в систему
     *
     * @param loginRequest данные для входа (username, password)
     * @return JWT токены и информация о пользователе
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.info("Попытка входа пользователя: {}", maskEmail(loginRequest.getEmail()));

        // Аутентификация пользователя (поле username содержит email)
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getEmail(),
                        loginRequest.getPassword()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Получение информации о пользователе
        UserDto userDto = userService.getUserByEmail(loginRequest.getEmail());
        UserResponse userResponse = userMapper.toResponse(userDto);

        // Генерация access JWT + opaque refresh-токена в БД
        String accessToken = jwtTokenProvider.generateAccessToken(authentication);
        String refreshToken = refreshTokenService.createRefreshToken(userDto.getId());

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000) // в секундах
                .tokenType("Bearer")
                .user(userResponse)
                .build();

        userService.updateLastActiveAt(userDto.getId());
        log.info("Успешный вход пользователя: {}", maskEmail(loginRequest.getEmail()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(refreshToken).toString())
                .body(response);
    }

    /**
     * Регистрация нового пользователя
     *
     * @param registerRequest данные для регистрации (email, name, password)
     * @return JWT токены и информация о новом пользователе
     */
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(
            @Valid @RequestBody RegisterRequest registerRequest,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        log.info("Попытка регистрации пользователя: {}", registerRequest.getName());

        // Резолвим язык писем: явное поле RegisterRequest.locale → Accept-Language → "ru"
        String emailLocale = resolveEmailLocale(registerRequest.getLocale(), acceptLanguage);

        // Создание нового пользователя
        UserDto newUserDto = UserDto.builder()
                .email(registerRequest.getEmail())
                .name(registerRequest.getName())
                .password(registerRequest.getPassword())
                .preferredEmailLocale(emailLocale)
                .build();

        UserDto createdUser = userService.createUser(newUserDto);

        // Генерация access JWT + opaque refresh-токена в БД
        String accessToken = jwtTokenProvider.generateAccessToken(createdUser.getEmail());
        String refreshToken = refreshTokenService.createRefreshToken(createdUser.getId());

        UserResponse userResponse = userMapper.toResponse(createdUser);

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000)
                .tokenType("Bearer")
                .user(userResponse)
                .build();

        log.info("Успешная регистрация пользователя: {}", registerRequest.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(refreshToken).toString())
                .body(response);
    }

    /**
     * Обновление access токена с помощью refresh токена
     *
     * @param refreshTokenRequest refresh токен
     * @return новые JWT токены
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest refreshTokenRequest,
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie) {
        log.debug("POST /api/auth/refresh — запрос получен");

        // Refresh-токен приходит из тела (Android) либо из HttpOnly-cookie (веб, #259).
        String rawToken = resolveRefreshToken(
                refreshTokenRequest != null ? refreshTokenRequest.getRefreshToken() : null,
                refreshCookie);

        // Ротация opaque refresh-токена (валидация + reuse detection внутри сервиса)
        RefreshTokenRotationResult result = refreshTokenService.rotateRefreshToken(rawToken);

        // Генерация нового access JWT
        String newAccessToken = jwtTokenProvider.generateAccessToken(result.email());

        // Получение информации о пользователе
        UserDto userDto = userService.getUserByEmail(result.email());
        UserResponse userResponse = userMapper.toResponse(userDto);

        LoginResponse response = LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(result.newRawToken())
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000)
                .tokenType("Bearer")
                .user(userResponse)
                .build();

        userService.updateLastActiveAt(userDto.getId());
        log.info("Успешное обновление токена для пользователя: {}", maskEmail(result.email()));
        // Ротация обновляет refresh-cookie новым значением
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.newRawToken()).toString())
                .body(response);
    }

    /**
     * Выход из системы — инвалидация access-токена и отзыв refresh-токена
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) LogoutRequest logoutRequest,
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie) {

        // Blacklist текущего access-токена
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String accessToken = authHeader.substring(7);
        tokenBlacklistService.blacklistAccessToken(
                accessToken, jwtTokenProvider.getExpirationFromToken(accessToken));

        // Отзыв refresh-токена: из тела (Android) либо из HttpOnly-cookie (веб, #259)
        String bodyRefresh = logoutRequest != null ? logoutRequest.getRefreshToken() : null;
        String refreshToRevoke = (bodyRefresh != null && !bodyRefresh.isBlank()) ? bodyRefresh : refreshCookie;
        if (refreshToRevoke != null && !refreshToRevoke.isBlank()) {
            refreshTokenService.revokeByRawToken(refreshToRevoke);
        }

        log.info("Выход пользователя: {}", maskEmail(userDetails.getUsername()));
        // Гасим refresh-cookie у веб-клиента (Max-Age=0), чтобы браузер её удалил
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(Map.of("message", "Выход выполнен"));
    }

    /**
     * Подтверждение email по токену из ссылки
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        userService.verifyEmail(request.getToken());
        return ResponseEntity.ok(Map.of("message", "Email подтверждён"));
    }

    /**
     * Повторная отправка письма верификации (требует JWT)
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userService.getUserByEmail(userDetails.getUsername()).getId();
        userService.resendVerificationEmail(userId);
        return ResponseEntity.ok(Map.of("message", "Письмо отправлено"));
    }

    /**
     * Запрос сброса пароля — отправка письма (всегда 200)
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        userService.initiatePasswordReset(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "Если аккаунт существует, письмо отправлено"));
    }

    /**
     * Установка нового пароля по токену из ссылки
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request.getToken(), request.getPassword());
        return ResponseEntity.ok(Map.of("message", "Пароль изменён"));
    }

    /**
     * Смена email с повторной верификацией (требует JWT)
     */
    @PostMapping("/change-email")
    public ResponseEntity<Map<String, String>> changeEmail(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangeEmailRequest request) {
        Long userId = userService.getUserByEmail(userDetails.getUsername()).getId();
        userService.changeEmail(userId, request.getEmail());
        return ResponseEntity.ok(Map.of("message", "Письмо подтверждения отправлено на новый email"));
    }

    /**
     * Резолв языка писем при регистрации:
     *   1. Если клиент явно прислал {@code RegisterRequest.locale} (короче 8 символов) — берём его.
     *   2. Иначе парсим первый язык из {@code Accept-Language} (e.g. "en-US,en;q=0.9,ru;q=0.8" → "en-US").
     *      Обрезаем до 8 символов на случай длинных тэгов вроде "zh-Hant-TW".
     *      Wildcard "*" игнорируется (бессмысленен как локаль).
     *   3. Fallback "ru" — если ни клиент, ни header ничего не дали.
     * <p>
     * Package-private для unit-тестирования.
     */
    String resolveEmailLocale(String requested, String acceptLanguage) {
        if (requested != null && !requested.isBlank()) {
            return requested.length() > 8 ? requested.substring(0, 8) : requested;
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                var ranges = java.util.Locale.LanguageRange.parse(acceptLanguage);
                if (!ranges.isEmpty()) {
                    String range = ranges.get(0).getRange();
                    if ("*".equals(range)) {
                        return "ru"; // Wildcard как локаль не имеет смысла
                    }
                    return range.length() > 8 ? range.substring(0, 8) : range;
                }
            } catch (IllegalArgumentException ignored) {
                // Битый Accept-Language — fallback ниже
            }
        }
        return "ru";
    }

    /**
     * Строит HttpOnly-cookie с refresh-токеном для веб-клиента (#259).
     * SameSite=Strict + Path=/api/auth: cookie уходит только на auth-эндпоинты
     * и только при same-site запросах — CSRF на /refresh практически закрыт.
     * Secure управляется {@code jwt.refresh-cookie-secure} (true в production).
     */
    private ResponseCookie buildRefreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(jwtProperties.getRefreshTokenExpiration() / 1000)
                .build();
    }

    /**
     * Cookie-«ластик»: то же имя/path/атрибуты, но пустое значение и Max-Age=0 —
     * браузер немедленно удаляет refresh-cookie при выходе.
     */
    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    /**
     * Извлекает refresh-токен из двух источников: тело запроса (Android) имеет
     * приоритет над HttpOnly-cookie (веб). Если токена нет нигде — 401.
     */
    private String resolveRefreshToken(String bodyToken, String cookieToken) {
        if (bodyToken != null && !bodyToken.isBlank()) {
            return bodyToken;
        }
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        throw new BadCredentialsException("Refresh-токен не предоставлен");
    }
}
