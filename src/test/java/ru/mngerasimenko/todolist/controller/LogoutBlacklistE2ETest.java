package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.UserService;
import ru.mngerasimenko.todolist.util.TokenUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E-тест сценария logout → blacklist → последующий запрос.
 *
 * <p>Поднимает весь Spring-контекст (PostgreSQL + Redis в Testcontainers),
 * проверяет, что бин {@code TokenBlacklistServiceRedis} корректно подцеплен
 * в {@code JwtAuthenticationFilter} и весь конвейер работает end-to-end:
 *
 * <ol>
 *     <li>Валидный токен пропускает {@code GET /api/users/me} → 200.</li>
 *     <li>{@code POST /api/auth/logout} кладёт токен в Redis blacklist → 200.</li>
 *     <li>Тот же токен на {@code GET /api/users/me} → 401.</li>
 *     <li>В Redis реально лежит ключ {@code todo:blacklist:{sha256(token)}}.</li>
 * </ol>
 *
 * <p>Unit-тесты и изолированный интеграционный тест Redis-сервиса этого не
 * покрывают: они не гарантируют, что именно Redis-реализация подцеплена
 * Spring'ом как {@code TokenBlacklistService}, и что фильтр её вызывает.
 */
@Tag("integration")
@AutoConfigureMockMvc
class LogoutBlacklistE2ETest extends AbstractIntegrationTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        redis.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
        // Фиксируем валидный base64-секрет (окружение может содержать URL-safe
        // секрет с «_» / «-», который падает в Decoders.BASE64.decode(...)).
        registry.add("jwt.secret", () -> "bXlTZWNyZXRLZXlGb3JKd3RUb2tlbkdlbmVyYXRpb25NdXN0QmVMb25nRW5vdWdoMjU2Qml0cw==");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * SMTP в тестах недоступен — глушим отправку verification email,
     * чтобы createUser(...) не падал/не висел.
     */
    @MockitoBean
    private EmailService emailService;

    @Test
    void logout_BlacklistsAccessToken_AndSubsequentRequestReturns401() throws Exception {
        // Арендуем уникальный email, чтобы тест был идемпотентным при повторах
        String email = "logout-e2e-" + System.nanoTime() + "@test.local";

        UserDto newUser = UserDto.builder()
                .email(email)
                .name("LogoutE2E-" + System.nanoTime())
                .password("TestPass123!")
                .build();
        userService.createUser(newUser);

        String accessToken = jwtTokenProvider.generateAccessToken(email);
        String bearer = "Bearer " + accessToken;

        // 1. Свежий токен пропускает фильтр
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(status().isOk());

        // 2. logout — 200
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // 3. В Redis реально появился ключ blacklist
        String expectedKey = "todo:blacklist:" + TokenUtils.sha256(accessToken);
        assertThat(redisTemplate.hasKey(expectedKey))
                .as("После logout в Redis должен появиться ключ blacklist")
                .isTrue();

        // 4. Тот же токен теперь отклоняется фильтром
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }
}
