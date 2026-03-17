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

    /**
     * Вход пользователя в систему
     *
     * @param loginRequest данные для входа (username, password)
     * @return JWT токены и информация о пользователе
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.info("Попытка входа пользователя: {}", loginRequest.getUsername());

        // Аутентификация пользователя
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getUsername(),
                        loginRequest.getPassword()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Генерация токенов
        String accessToken = jwtTokenProvider.generateAccessToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken(loginRequest.getUsername());

        // Получение информации о пользователе
        UserDto userDto = userService.getUserByUserName(loginRequest.getUsername());
        UserResponse userResponse = userMapper.toResponse(userDto);

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000) // в секундах
                .tokenType("Bearer")
                .user(userResponse)
                .build();

        log.info("Успешный вход пользователя: {}", loginRequest.getUsername());
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

        // Генерация токенов для нового пользователя
        String accessToken = jwtTokenProvider.generateAccessToken(createdUser.getName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(createdUser.getName());

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
        String refreshToken = refreshTokenRequest.getRefreshToken();

        // Валидация refresh токена
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("POST /api/auth/refresh — refresh токен невалиден, возвращаем 401");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Извлечение username из токена
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        log.debug("POST /api/auth/refresh — токен валиден, пользователь: {}", username);

        // Генерация новых токенов
        String newAccessToken = jwtTokenProvider.generateAccessToken(username);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(username);

        // Получение информации о пользователе
        UserDto userDto = userService.getUserByUserName(username);
        UserResponse userResponse = userMapper.toResponse(userDto);

        LoginResponse response = LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000)
                .tokenType("Bearer")
                .user(userResponse)
                .build();

        log.info("Успешное обновление токена для пользователя: {}", username);
        return ResponseEntity.ok(response);
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
        Long userId = userService.getUserByUserName(userDetails.getUsername()).getId();
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
        Long userId = userService.getUserByUserName(userDetails.getUsername()).getId();
        userService.changeEmail(userId, request.getEmail());
        return ResponseEntity.ok(Map.of("message", "Письмо подтверждения отправлено на новый email"));
    }
}
