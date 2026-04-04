package ru.mngerasimenko.todolist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.dto.auth.*;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.RefreshTokenService;
import ru.mngerasimenko.todolist.service.RefreshTokenService.RefreshTokenRotationResult;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты для AuthController
 * Проверяют работу эндпоинтов аутентификации: login, register, refresh
 */
@WebMvcTest(AuthController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    private UserDto testUserDto;
    private UserResponse testUserResponse;

    @BeforeEach
    void setUp() {
        // Подготовка тестовых данных
        testUserDto = UserDto.builder()
                .id(1L)
                .email("test@example.com")
                .name("testUser")
                .password("hashedPassword")
                .build();

        testUserResponse = UserResponse.builder()
                .id(1L)
                .email("test@example.com")
                .name("testUser")
                .build();

        // Настройка JwtProperties
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(3600000L); // 1 час
    }

    // ==================== LOGIN TESTS ====================

    @Test
    void login_ValidCredentials_ReturnsTokensAndUserInfo() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com",
                "password123",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class)))
                .thenReturn("access-token-123");
        when(refreshTokenService.createRefreshToken(1L))
                .thenReturn("refresh-token-123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("access-token-123"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-token-123"))
                .andExpect(jsonPath("$.expires_in").value(3600)) // 3600000 / 1000
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.name").value("testUser"));
    }

    @Test
    void login_InvalidCredentials_ReturnsUnauthorized() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("wrongPassword")
                .build();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Неверный email или пароль"));
    }

    @Test
    void login_EmptyUsername_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void login_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void login_NullFields_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"email\": null, \"password\": null}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ==================== REGISTER TESTS ====================

    @Test
    void register_ValidData_ReturnsCreatedWithTokens() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("new@example.com")
                .name("newUser")
                .password("password123")
                .build();

        UserDto newUserDto = UserDto.builder()
                .id(2L)
                .email("new@example.com")
                .name("newUser")
                .password("hashedPassword")
                .build();

        UserResponse newUserResponse = UserResponse.builder()
                .id(2L)
                .email("new@example.com")
                .name("newUser")
                .build();

        when(userService.createUser(any(UserDto.class))).thenReturn(newUserDto);
        when(jwtTokenProvider.generateAccessToken("new@example.com")).thenReturn("new-access-token");
        when(refreshTokenService.createRefreshToken(2L)).thenReturn("new-refresh-token");
        when(userMapper.toResponse(newUserDto)).thenReturn(newUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(2))
                .andExpect(jsonPath("$.user.email").value("new@example.com"))
                .andExpect(jsonPath("$.user.name").value("newUser"));
    }

    @Test
    void register_DuplicateEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("existing@example.com")
                .name("newUser")
                .password("password123")
                .build();

        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new IllegalArgumentException("Email existing@example.com is already taken"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email existing@example.com is already taken"));
    }

    @Test
    void register_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("")
                .name("newUser")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void register_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("invalid-email")
                .name("newUser")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void register_EmptyName_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.name").exists());
    }

    @Test
    void register_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("newUser")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void register_AllFieldsEmpty_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("")
                .name("")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists())
                .andExpect(jsonPath("$.message.name").exists())
                .andExpect(jsonPath("$.message.password").exists());
    }

    // ==================== REFRESH TOKEN TESTS ====================

    @Test
    void refresh_ValidToken_ReturnsNewTokens() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("valid-refresh-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("valid-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh-token", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("new-access-token");
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.user.name").value("testUser"));
    }

    @Test
    void refresh_InvalidToken_ReturnsUnauthorized() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("invalid-refresh-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("invalid-refresh-token"))
                .thenThrow(new BadCredentialsException("Невалидный refresh-токен"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_EmptyToken_ReturnsBadRequest() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.refreshToken").exists());
    }

    @Test
    void refresh_NullToken_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"refreshToken\": null}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ==================== CONTENT TYPE TESTS ====================

    @Test
    void login_ReturnsJsonContentType() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com", "password123"
        );

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(userService.getUserByEmail(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class))).thenReturn("token");
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("refresh");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void register_ReturnsJsonContentType() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("testUser")
                .password("password123")
                .build();

        when(userService.createUser(any())).thenReturn(testUserDto);
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("token");
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("refresh");
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void refresh_ReturnsJsonContentType() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("valid-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("valid-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("token");
        when(userService.getUserByEmail(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    // ==================== VERIFY EMAIL TESTS ====================

    @Test
    void verifyEmail_ValidToken_ReturnsOk() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-123");

        doNothing().when(userService).verifyEmail("valid-token-123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email подтверждён"));
    }

    @Test
    void verifyEmail_ExpiredToken_ReturnsBadRequest() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("expired-token");

        doThrow(new TokenExpiredException("Токен истёк"))
                .when(userService).verifyEmail("expired-token");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Токен истёк"));
    }

    @Test
    void verifyEmail_EmptyToken_ReturnsBadRequest() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists());
    }

    @Test
    void verifyEmail_NullToken_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"token\": null}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ==================== RESEND VERIFICATION TESTS ====================

    @Test
    @WithMockUser(username = "user@example.com")
    void resendVerification_Authenticated_ReturnsOk() throws Exception {
        // Arrange — мокируем получение пользователя по email
        UserDto userDto = UserDto.builder().id(1L).name("user").email("user@example.com").build();
        when(userService.getUserByEmail("user@example.com")).thenReturn(userDto);
        doNothing().when(userService).resendVerificationEmail(1L);

        // Act & Assert
        mockMvc.perform(post("/api/auth/resend-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Письмо отправлено"));
    }

    @Test
    void resendVerification_NoAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert — без JWT-токена ожидаем 401
        mockMvc.perform(post("/api/auth/resend-verification"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== FORGOT PASSWORD TESTS ====================

    @Test
    void forgotPassword_ValidEmail_ReturnsOk() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("test@example.com");

        doNothing().when(userService).initiatePasswordReset("test@example.com");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Если аккаунт существует, письмо отправлено"));
    }

    @Test
    void forgotPassword_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void forgotPassword_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    // ==================== RESET PASSWORD TESTS ====================

    @Test
    void resetPassword_ValidData_ReturnsOk() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "newPassword123");

        doNothing().when(userService).resetPassword("valid-token", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Пароль изменён"));
    }

    @Test
    void resetPassword_ExpiredToken_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("expired-token", "newPassword123");

        doThrow(new TokenExpiredException("Токен сброса пароля истёк"))
                .when(userService).resetPassword("expired-token", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Токен сброса пароля истёк"));
    }

    @Test
    void resetPassword_EmptyToken_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists());
    }

    @Test
    void resetPassword_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void resetPassword_AllFieldsEmpty_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("", "");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void resetPassword_TooShortPassword_ReturnsBadRequest() throws Exception {
        // Arrange — пароль короче 3 символов
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "ab");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    // ==================== CHANGE EMAIL TESTS ====================

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_Authenticated_ReturnsOk() throws Exception {
        // Arrange — мокируем получение пользователя по email
        UserDto userDto = UserDto.builder().id(1L).name("user").email("user@example.com").build();
        when(userService.getUserByEmail("user@example.com")).thenReturn(userDto);
        doNothing().when(userService).changeEmail(1L, "new@example.com");

        ChangeEmailRequest request = new ChangeEmailRequest("new@example.com");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Письмо подтверждения отправлено на новый email"));

        verify(userService, times(1)).changeEmail(1L, "new@example.com");
    }

    @Test
    void changeEmail_NoAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert — без JWT-токена ожидаем 401
        ChangeEmailRequest request = new ChangeEmailRequest("new@example.com");

        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange — пустой email
        ChangeEmailRequest request = new ChangeEmailRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange — некорректный формат email
        ChangeEmailRequest request = new ChangeEmailRequest("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    // ==================== LOGOUT TESTS ====================

    @Test
    @WithMockUser(username = "test@example.com")
    void logout_WithRefreshToken_ReturnsOk() throws Exception {
        // Arrange
        LogoutRequest logoutRequest = LogoutRequest.builder()
                .refreshToken("some-refresh-token")
                .build();

        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Выход выполнен"));

        verify(tokenBlacklistService).blacklistAccessToken(eq("test-access-token"), any());
        verify(refreshTokenService).revokeByRawToken("some-refresh-token");
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void logout_WithoutRefreshToken_ReturnsOk() throws Exception {
        // Arrange
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Выход выполнен"));

        verify(tokenBlacklistService).blacklistAccessToken(eq("test-access-token"), any());
        verify(refreshTokenService, never()).revokeByRawToken(anyString());
    }

    @Test
    void logout_Unauthenticated_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
