package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
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
     * Вход пользователя в систему
     *
     * @param loginRequest данные для входа (username, password)
     * @return JWT токены и информация о пользователе
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.info("Попытка входа пользователя: {}", loginRequest.getEmail());

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

        log.info("Успешный вход пользователя: {}", loginRequest.getEmail());
        return ResponseEntity.ok(response);
    }

    /**
     * Регистрация нового пользователя
     *
     * @param registerRequest данные для регистрации (email, name, password)
     * @return JWT токены и информация о новом пользователе
     */
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        log.info("Попытка регистрации пользователя: {}", registerRequest.getName());

        // Создание нового пользователя
        UserDto newUserDto = UserDto.builder()
                .email(registerRequest.getEmail())
                .name(registerRequest.getName())
                .password(registerRequest.getPassword())
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
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Обновление access токена с помощью refresh токена
     *
     * @param refreshTokenRequest refresh токен
     * @return новые JWT токены
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest refreshTokenRequest) {
        log.debug("POST /api/auth/refresh — запрос получен");

        // Ротация opaque refresh-токена (валидация + reuse detection внутри сервиса)
        RefreshTokenRotationResult result = refreshTokenService.rotateRefreshToken(
                refreshTokenRequest.getRefreshToken());

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

        log.info("Успешное обновление токена для пользователя: {}", result.email());
        return ResponseEntity.ok(response);
    }

    /**
     * Выход из системы — инвалидация access-токена и отзыв refresh-токена
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) LogoutRequest logoutRequest) {

        // Blacklist текущего access-токена
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String accessToken = authHeader.substring(7);
        tokenBlacklistService.blacklistAccessToken(
                accessToken, jwtTokenProvider.getExpirationFromToken(accessToken));

        // Отзыв refresh-токена если передан
        if (logoutRequest != null && logoutRequest.getRefreshToken() != null
                && !logoutRequest.getRefreshToken().isBlank()) {
            refreshTokenService.revokeByRawToken(logoutRequest.getRefreshToken());
        }

        log.info("Выход пользователя: {}", userDetails.getUsername());
        return ResponseEntity.ok(Map.of("message", "Выход выполнен"));
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
}
