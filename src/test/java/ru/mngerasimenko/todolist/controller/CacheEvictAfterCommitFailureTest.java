package ru.mngerasimenko.todolist.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.UserServiceImpl;
import ru.mngerasimenko.todolist.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Парный тест устойчивости afterCommit-сброса кэша (задача 522): сбой Redis в ПЕРВОМ
 * колбэке не роняет HTTP-ответ и не срывает ВТОРОЙ колбэк.
 *
 * <p>Сценарий: PUT /api/users/{id} со сменой email регистрирует два afterCommit-колбэка —
 * evict по старому email и по новому. Мок Redis бросает {@link RedisConnectionFailureException}
 * на первом же evict. Проверяется всё три требования приёмки разом:</p>
 * <ol>
 *   <li>HTTP-ответ НЕ 500 — исключение из afterCommit не доходит до клиента
 *       (коммит уже прошёл, 500 заставила бы клиент повторить применённую правку);</li>
 *   <li>второй колбэк (evict по новому email) всё равно вызван — без перехвата в первом
 *       Spring оборвёт фазу afterCommit и колбэки после упавшего не выполнит;</li>
 *   <li>user-auth обоих колбэков тоже вызван — сбой users-me не должен оставлять старый hash;</li>
 *   <li>сбой залогирован со стектрейсом (throwable в событии лога), а не текстом одним.</li>
 * </ol>
 *
 * <p>H2-профиль вместо Testcontainers: Redis подменён на мок через {@code @MockitoBean},
 * Postgres не нужен — контекст поднимается на H2 как в других тестах профиля {@code test}.
 * {@code @MockitoBean(name = "cacheManager")} подменяет ровно бин {@code RedisCacheConfig#cacheManager}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CacheEvictAfterCommitFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** SMTP в тестах недоступен — глушим отправку verification email при createUser. */
    @MockitoBean
    private EmailService emailService;

    /** Подменяет cacheManager целиком: getCache отдаёт моки, evict которых бросает Redis-сбой. */
    @MockitoBean(name = "cacheManager")
    private CacheManager cacheManager;

    private Cache usersMe;
    private Cache userAuth;

    private Logger serviceLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        usersMe = mock(Cache.class);
        userAuth = mock(Cache.class);
        // Падает evict users-me. user-auth того же колбэка всё равно должен быть вызван.
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(usersMe).evict(any());
        lenient().when(cacheManager.getCache(RedisCacheConfig.USERS_ME)).thenReturn(usersMe);
        lenient().when(cacheManager.getCache(RedisCacheConfig.USER_AUTH)).thenReturn(userAuth);

        serviceLogger = (Logger) LoggerFactory.getLogger(UserServiceImpl.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void updateUserChangeEmail_RedisDownAfterCommit_ResponseIsNot500_AndSecondCallbackRuns()
            throws Exception {
        String oldEmail = "aftercommit-" + System.nanoTime() + "@test.local";
        UserDto created = userService.createUser(UserDto.builder()
                .email(oldEmail)
                .password("TestPass123!")
                .name("AfterCommit-" + System.nanoTime())
                .build());
        String newEmail = "aftercommit-new-" + System.nanoTime() + "@test.local";

        UserRequest request = UserRequest.builder()
                .id(created.getId())
                .email(newEmail)
                .password("TestPass123!")
                .name("Renamed-" + System.nanoTime())
                .build();

        String bearer = "Bearer " + jwtTokenProvider.generateAccessToken(oldEmail);

        // 1) Ответ не 500: до починки исключение из afterCommit долетает до клиента
        //    (или как 500 через handler, или как NestedServletException из perform).
        mockMvc.perform(put("/api/users/" + created.getId())
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 2) Второй колбэк (evict по НОВОМУ email) вызван несмотря на сбой первого:
        //    без перехвата в первом колбэке фаза afterCommit обрывается на нём.
        verify(usersMe).evict(oldEmail.toLowerCase());
        verify(usersMe).evict(newEmail.toLowerCase());
        verify(userAuth).evict(oldEmail.toLowerCase());
        verify(userAuth).evict(newEmail.toLowerCase());

        // 3) Сбой залогирован со стектрейсом, а не текстом одним.
        ILoggingEvent failure = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("afterCommit failed"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "сбой Redis в afterCommit не залогирован; лог: " + appender.list));
        assertThat(failure.getThrowableProxy()).isNotNull();
        assertThat(failure.getThrowableProxy().getClassName())
                .isEqualTo(RedisConnectionFailureException.class.getName());
        assertThat(failure.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
    }
}
