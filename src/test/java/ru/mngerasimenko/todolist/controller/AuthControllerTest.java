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
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.dto.auth.LoginRequest;
import ru.mngerasimenko.todolist.dto.auth.RefreshTokenRequest;
import ru.mngerasimenko.todolist.dto.auth.RegisterRequest;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
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
                .username("testUser")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "testUser",
                "password123",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class)))
                .thenReturn("access-token-123");
        when(jwtTokenProvider.generateRefreshToken("testUser"))
                .thenReturn("refresh-token-123");
        when(userService.getUserByUserName("testUser")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

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
                .username("testUser")
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
                .andExpect(jsonPath("$.message").value("Bad credentials"));
    }

    @Test
    void login_EmptyUsername_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .username("")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists());
    }

    @Test
    void login_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testUser")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").exists());
    }

    @Test
    void login_NullFields_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"username\": null, \"password\": null}";

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
        when(jwtTokenProvider.generateAccessToken("newUser")).thenReturn("new-access-token");
        when(jwtTokenProvider.generateRefreshToken("newUser")).thenReturn("new-refresh-token");
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
                .andExpect(jsonPath("$.email").exists());
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
                .andExpect(jsonPath("$.email").exists());
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
                .andExpect(jsonPath("$.name").exists());
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
                .andExpect(jsonPath("$.password").exists());
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
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.password").exists());
    }

    // ==================== REFRESH TOKEN TESTS ====================

    @Test
    void refresh_ValidToken_ReturnsNewTokens() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("valid-refresh-token")
                .build();

        when(jwtTokenProvider.validateToken("valid-refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid-refresh-token")).thenReturn("testUser");
        when(jwtTokenProvider.generateAccessToken("testUser")).thenReturn("new-access-token");
        when(jwtTokenProvider.generateRefreshToken("testUser")).thenReturn("new-refresh-token");
        when(userService.getUserByUserName("testUser")).thenReturn(testUserDto);
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

        when(jwtTokenProvider.validateToken("invalid-refresh-token")).thenReturn(false);

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
                .andExpect(jsonPath("$.refreshToken").exists());
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
                .username("testUser")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "testUser", "password123"
        );

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class))).thenReturn("token");
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh");
        when(userService.getUserByUserName(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

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
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh");
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

        when(jwtTokenProvider.validateToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(anyString())).thenReturn("testUser");
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("token");
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh");
        when(userService.getUserByUserName(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }
}
