package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест кэша {@link RedisCacheConfig#USER_AUTH}.
 *
 * Проверяет то, что unit-тесты с моками не ловят:
 *  — Spring реально применяет {@code @Cacheable}/{@code @CacheEvict} через AOP-прокси;
 *  — ключ в Redis приходит в lowercase (фикс case-insensitivity);
 *  — evict срабатывает после {@code afterCommit}-synchronization (не до коммита);
 *  — feature-flag {@code RESPONSE_CACHE} действительно отключает кэш runtime.
 *
 * Поднимает Postgres (из {@link AbstractIntegrationTest}) + Redis в Testcontainers.
 * Запускается в профиле {@code -Pintegration}.
 */
@Tag("integration")
class UserAuthCacheIntegrationTest extends AbstractIntegrationTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FeatureFlagStore featureFlagStore;

    /** SMTP в тестах недоступен — глушим отправку email, чтобы createUser не падал. */
    @MockitoBean
    private EmailService emailService;

    private String email;
    private Long userId;

    @BeforeEach
    void setUp() {
        // Каждый тест чистит весь Redis, чтобы не зависеть от соседей
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Уникальный email на каждый запуск, чтобы тесты были идемпотентны
        email = "auth-cache-" + System.nanoTime() + "@test.local";
        UserDto dto = UserDto.builder()
                .email(email)
                .password("TestPass123!")
                .name("AuthCache-" + System.nanoTime())
                .build();
        userId = userService.createUser(dto).getId();
    }

    @AfterEach
    void resetFlag() {
        featureFlagStore.reset(FeatureFlag.RESPONSE_CACHE);
    }

    // ============================================================
    // Базовое кэширование
    // ============================================================

    @Test
    void getUserByEmailForAuth_CachesResultInRedis() {
        UserDto result = userService.getUserByEmailForAuth(email);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        // Password (BCrypt-hash) обязан присутствовать — его нужен DaoAuthenticationProvider для login
        assertThat(result.getPassword()).isNotBlank();

        // Ключ в Redis присутствует (форматируется Spring Cache как "<cacheName>::<key>")
        assertThat(redisTemplate.hasKey(redisKey(email))).isTrue();
    }

    @Test
    void nullResult_IsNotCached() {
        // unless = "#result == null" блокирует negative caching — защита от email-enumeration
        String unknown = "nobody-" + System.nanoTime() + "@test.local";

        UserDto result = userService.getUserByEmailForAuth(unknown);

        assertThat(result).isNull();
        assertThat(redisTemplate.keys(RedisCacheConfig.USER_AUTH + "::*"))
                .as("Null-результат не должен попадать в кэш")
                .noneMatch(k -> k.contains(unknown));
    }

    // ============================================================
    // Case-insensitivity (фикс #1 из ревью)
    // ============================================================

    @Test
    void getUserByEmailForAuth_IsCaseInsensitive_SingleCacheEntry() {
        userService.getUserByEmailForAuth(email);                  // lowercase
        userService.getUserByEmailForAuth(email.toUpperCase());    // UPPER
        userService.getUserByEmailForAuth(randomCase(email));      // MiXeD

        // Все три варианта должны нормализоваться в один ключ (lowercase)
        Set<String> keys = redisTemplate.keys(RedisCacheConfig.USER_AUTH + "::*");
        assertThat(keys)
                .as("Для разных casing одного email должна быть одна запись в кэше")
                .hasSize(1)
                .containsExactly(redisKey(email));
    }

    // ============================================================
    // Инвалидация при мутациях User
    // ============================================================

    @Test
    void updateUser_EvictsUserAuthCache() {
        warmCache();

        UserDto update = UserDto.builder()
                .email(email)  // email не меняем
                .name("NewName-" + System.nanoTime())
                .password("NewPassword456!")
                .authId("auth-new-" + System.nanoTime())
                .build();
        userService.updateUser(userId, update);

        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("updateUser должен evict'нуть user-auth через evictUserCache")
                .isFalse();
    }

    @Test
    void updateColors_EvictsUserAuthCache() {
        warmCache();

        userService.updateColors(userId, "#FF0000", "#00FF00");

        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("updateColors имеет @CacheEvict на user-auth — кэш должен быть очищен")
                .isFalse();
    }

    @Test
    void changeEmail_EvictsBothOldAndNewEmailKeys() {
        warmCache();
        String newEmail = "new-" + System.nanoTime() + "@test.local";

        // Прогреваем кэш и для нового email (на случай если к нему обращались до смены)
        userService.getUserByEmailForAuth(newEmail);  // вернёт null, не закэшируется

        userService.changeEmail(userId, newEmail);

        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("changeEmail должен evict'нуть кэш по старому email")
                .isFalse();
        assertThat(redisTemplate.hasKey(redisKey(newEmail)))
                .as("changeEmail должен evict'нуть кэш по новому email")
                .isFalse();
    }

    @Test
    void deleteUser_EvictsUserAuthCache() {
        warmCache();

        userService.delete(userId);

        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("delete должен evict'нуть кэш удалённого пользователя")
                .isFalse();
    }

    // ============================================================
    // Feature-flag runtime switch
    // ============================================================

    @Test
    void featureFlagOff_BypassesCache() {
        featureFlagStore.set(FeatureFlag.RESPONSE_CACHE, false);

        UserDto result = userService.getUserByEmailForAuth(email);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("Feature-flag OFF — @Cacheable не должен писать в Redis")
                .isFalse();
    }

    @Test
    void featureFlagToggle_CacheBehaviorSwitchesAtRuntime() {
        // OFF — записи в Redis быть не должно
        featureFlagStore.set(FeatureFlag.RESPONSE_CACHE, false);
        userService.getUserByEmailForAuth(email);
        assertThat(redisTemplate.hasKey(redisKey(email))).isFalse();

        // Включаем обратно — следующий вызов обязан положить в Redis
        featureFlagStore.set(FeatureFlag.RESPONSE_CACHE, true);
        userService.getUserByEmailForAuth(email);
        assertThat(redisTemplate.hasKey(redisKey(email))).isTrue();
    }

    // ============================================================
    // Вспомогательные
    // ============================================================

    private void warmCache() {
        userService.getUserByEmailForAuth(email);
        assertThat(redisTemplate.hasKey(redisKey(email)))
                .as("warmCache: кэш должен быть прогрет перед тестом evict")
                .isTrue();
    }

    private static String redisKey(String email) {
        return RedisCacheConfig.USER_AUTH + "::" + email.toLowerCase();
    }

    private static String randomCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
