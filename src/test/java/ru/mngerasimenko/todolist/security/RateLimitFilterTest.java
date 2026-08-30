package ru.mngerasimenko.todolist.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.service.RedisHealthService;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для RateLimitFilter.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private BucketProviderInMemory provider;
    private FilterChain filterChain;
    private RedisHealthService healthAlwaysUp;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
        props.setRegister(new RateLimitProperties.EndpointLimit(2, 3600));
        props.setRefresh(new RateLimitProperties.EndpointLimit(5, 60));
        props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
        provider = new BucketProviderInMemory();
        FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
        when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);
        healthAlwaysUp = mock(RedisHealthService.class);
        when(healthAlwaysUp.isRedisHealthy()).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        // 4-й аргумент (in-memory fallback) — null: основной провайдер уже in-memory,
        // fallback в memory-режиме не нужен. health/meter — стандартный mock.
        filter = new RateLimitFilter(props, provider, flagStore, null, healthAlwaysUp, meterRegistry);
        filterChain = mock(FilterChain.class);
    }

    private MockHttpServletRequest createRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("192.168.1.1");
        return request;
    }

    // --- Тесты лимита на login ---

    @Nested
    @DisplayName("POST /api/auth/login — лимит 3 запроса/мин")
    class LoginRateLimit {

        @Test
        @DisplayName("Запросы в пределах лимита проходят")
        void requestsWithinLimit_ShouldPass() throws ServletException, IOException {
            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest request = createRequest("POST", "/api/auth/login");
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilterInternal(request, response, filterChain);
                assertThat(response.getStatus()).isEqualTo(200);
            }
            verify(filterChain, times(3)).doFilter(any(), any());
        }

        @Test
        @DisplayName("Превышение лимита возвращает 429")
        void exceedingLimit_ShouldReturn429() throws ServletException, IOException {
            // Исчерпываем лимит
            for (int i = 0; i < 3; i++) {
                filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                        new MockHttpServletResponse(), filterChain);
            }

            // Следующий запрос — 429
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(createRequest("POST", "/api/auth/login"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getHeader("Retry-After")).isNotNull();
            assertThat(response.getHeader("X-Rate-Limit-Remaining")).isEqualTo("0");
            assertThat(response.getContentAsString()).contains("Too Many Requests");

            // filterChain НЕ вызывается для заблокированного запроса
            verify(filterChain, times(3)).doFilter(any(), any());
        }

        @Test
        @DisplayName("Разные IP имеют независимые лимиты")
        void differentIPs_HaveIndependentLimits() throws ServletException, IOException {
            // IP #1 исчерпывает лимит
            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest request = createRequest("POST", "/api/auth/login");
                request.setRemoteAddr("10.0.0.1");
                filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
            }

            // IP #2 — лимит не затронут
            MockHttpServletRequest request2 = createRequest("POST", "/api/auth/login");
            request2.setRemoteAddr("10.0.0.2");
            MockHttpServletResponse response2 = new MockHttpServletResponse();
            filter.doFilterInternal(request2, response2, filterChain);

            assertThat(response2.getStatus()).isEqualTo(200);
            verify(filterChain, times(4)).doFilter(any(), any());
        }
    }

    // --- Тесты лимита на register ---

    @Nested
    @DisplayName("POST /api/auth/register — лимит 2 запроса/час")
    class RegisterRateLimit {

        @Test
        @DisplayName("Превышение лимита регистрации возвращает 429")
        void exceedingRegisterLimit_ShouldReturn429() throws ServletException, IOException {
            for (int i = 0; i < 2; i++) {
                filter.doFilterInternal(createRequest("POST", "/api/auth/register"),
                        new MockHttpServletResponse(), filterChain);
            }

            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(createRequest("POST", "/api/auth/register"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            verify(filterChain, times(2)).doFilter(any(), any());
        }
    }

    // --- Тесты общего лимита ---

    @Nested
    @DisplayName("Общий лимит для /api/** — 10 запросов/мин")
    class GeneralRateLimit {

        @Test
        @DisplayName("Превышение общего лимита возвращает 429")
        void exceedingGeneralLimit_ShouldReturn429() throws ServletException, IOException {
            for (int i = 0; i < 10; i++) {
                filter.doFilterInternal(createRequest("GET", "/api/todos/all"),
                        new MockHttpServletResponse(), filterChain);
            }

            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(createRequest("GET", "/api/todos/all"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("Разные эндпоинты одного IP делят общий лимит")
        void differentEndpoints_ShareGeneralLimit() throws ServletException, IOException {
            // 5 запросов на /api/todos/all
            for (int i = 0; i < 5; i++) {
                filter.doFilterInternal(createRequest("GET", "/api/todos/all"),
                        new MockHttpServletResponse(), filterChain);
            }

            // 5 запросов на /api/users/me
            for (int i = 0; i < 5; i++) {
                filter.doFilterInternal(createRequest("GET", "/api/users/me"),
                        new MockHttpServletResponse(), filterChain);
            }

            // 11-й запрос — 429
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(createRequest("GET", "/api/todos/1"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
        }
    }

    // --- Тесты пропуска эндпоинтов ---

    @Nested
    @DisplayName("Эндпоинты без ограничений")
    class UnlimitedEndpoints {

        @Test
        @DisplayName("/api/status проходит без rate limiting")
        void statusEndpoint_ShouldNotBeLimited() throws ServletException, IOException {
            for (int i = 0; i < 20; i++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilterInternal(createRequest("GET", "/api/status"), response, filterChain);
                assertThat(response.getStatus()).isEqualTo(200);
            }
            verify(filterChain, times(20)).doFilter(any(), any());
        }

        @Test
        @DisplayName("/api/swagger-ui проходит без rate limiting")
        void swaggerEndpoint_ShouldNotBeLimited() throws ServletException, IOException {
            for (int i = 0; i < 20; i++) {
                filter.doFilterInternal(createRequest("GET", "/api/swagger-ui/index.html"),
                        new MockHttpServletResponse(), filterChain);
            }
            verify(filterChain, times(20)).doFilter(any(), any());
        }

        @Test
        @DisplayName("Не-API запросы проходят без rate limiting")
        void nonApiRequests_ShouldNotBeLimited() throws ServletException, IOException {
            for (int i = 0; i < 20; i++) {
                filter.doFilterInternal(createRequest("GET", "/favicon.ico"),
                        new MockHttpServletResponse(), filterChain);
            }
            verify(filterChain, times(20)).doFilter(any(), any());
        }
    }

    // --- Тесты определения IP ---

    @Nested
    @DisplayName("Определение IP-адреса клиента")
    class ClientIpResolution {

        @Test
        @DisplayName("IP из X-Real-IP (доверенный заголовок от nginx)")
        void xRealIp_ShouldUseHeaderValue() {
            MockHttpServletRequest request = createRequest("GET", "/api/todos/all");
            request.addHeader("X-Real-IP", "203.0.113.50");

            String ip = filter.resolveClientIp(request);
            assertThat(ip).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("X-Forwarded-For игнорируется — используется remoteAddr")
        void xForwardedFor_ShouldBeIgnored() {
            MockHttpServletRequest request = createRequest("GET", "/api/todos/all");
            request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");

            String ip = filter.resolveClientIp(request);
            assertThat(ip).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("Без заголовков — используется remoteAddr")
        void noHeaders_ShouldUseRemoteAddr() {
            MockHttpServletRequest request = createRequest("GET", "/api/todos/all");

            String ip = filter.resolveClientIp(request);
            assertThat(ip).isEqualTo("192.168.1.1");
        }
    }

    // --- Тесты resolveBucketKey ---

    @Nested
    @DisplayName("Определение ключа bucket'а")
    class BucketKeyResolution {

        @Test
        @DisplayName("POST /api/auth/login → login:IP")
        void loginEndpoint() {
            String key = filter.resolveBucketKey("/api/auth/login", "POST", "1.2.3.4");
            assertThat(key).isEqualTo("login:1.2.3.4");
        }

        @Test
        @DisplayName("POST /api/auth/register → register:IP")
        void registerEndpoint() {
            String key = filter.resolveBucketKey("/api/auth/register", "POST", "1.2.3.4");
            assertThat(key).isEqualTo("register:1.2.3.4");
        }

        @Test
        @DisplayName("POST /api/auth/refresh → refresh:IP")
        void refreshEndpoint() {
            String key = filter.resolveBucketKey("/api/auth/refresh", "POST", "1.2.3.4");
            assertThat(key).isEqualTo("refresh:1.2.3.4");
        }

        @Test
        @DisplayName("POST /api/auth/change-password → changePassword:IP")
        void changePasswordEndpoint() {
            String key = filter.resolveBucketKey("/api/auth/change-password", "POST", "1.2.3.4");
            assertThat(key).isEqualTo("changePassword:1.2.3.4");
        }

        @Test
        @DisplayName("GET /api/todos/all → general:IP")
        void generalEndpoint() {
            String key = filter.resolveBucketKey("/api/todos/all", "GET", "1.2.3.4");
            assertThat(key).isEqualTo("general:1.2.3.4");
        }

        @Test
        @DisplayName("GET /api/suggestions/all → suggestions-all:IP")
        void suggestionsBulkEndpoint() {
            String key = filter.resolveBucketKey("/api/suggestions/all", "GET", "1.2.3.4");
            assertThat(key).isEqualTo("suggestions-all:1.2.3.4");
        }

        @Test
        @DisplayName("GET /api/suggestions → suggestions:IP")
        void suggestionsEndpoint() {
            String key = filter.resolveBucketKey("/api/suggestions", "GET", "1.2.3.4");
            assertThat(key).isEqualTo("suggestions:1.2.3.4");
        }

        @Test
        @DisplayName("Fail-safe: прочий /api/suggestions/* падает в suggestions, не в general")
        void suggestionsSubPath_FailsSafeToSuggestionsBucket() {
            // вариант, который exact-проверки не ловят (напр. trailing slash) — НЕ должен утечь в general
            assertThat(filter.resolveBucketKey("/api/suggestions/all/", "GET", "1.2.3.4"))
                    .isEqualTo("suggestions:1.2.3.4");
            assertThat(filter.resolveBucketKey("/api/suggestions/foo", "GET", "1.2.3.4"))
                    .isEqualTo("suggestions:1.2.3.4");
        }

        @Test
        @DisplayName("/api/status → null (без ограничений)")
        void statusEndpoint_ReturnsNull() {
            String key = filter.resolveBucketKey("/api/status", "GET", "1.2.3.4");
            assertThat(key).isNull();
        }

        @Test
        @DisplayName("/api/appName → null (без ограничений)")
        void appNameEndpoint_ReturnsNull() {
            String key = filter.resolveBucketKey("/api/appName", "GET", "1.2.3.4");
            assertThat(key).isNull();
        }
    }

    // --- Тесты очистки bucket'ов ---

    @Nested
    @DisplayName("Очистка неактивных bucket'ов")
    class BucketEviction {

        @Test
        @DisplayName("evictExpiredBuckets удаляет устаревшие bucket'ы")
        void evictExpiredBuckets_ShouldRemoveOldBuckets() throws ServletException, IOException {
            // Создаём bucket
            filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                    new MockHttpServletResponse(), filterChain);

            assertThat(provider.getActiveBucketCount()).isEqualTo(1);
            provider.evictExpiredBuckets(0);
            assertThat(provider.getActiveBucketCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("evictExpiredBuckets не удаляет свежие bucket'ы")
        void evictExpiredBuckets_ShouldKeepRecentBuckets() throws ServletException, IOException {
            filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                    new MockHttpServletResponse(), filterChain);

            // Очистка с большим временем жизни — не удалит
            provider.evictExpiredBuckets(Long.MAX_VALUE);
            assertThat(provider.getActiveBucketCount()).isEqualTo(1);
        }
    }

    // --- Тест заголовка X-Rate-Limit-Remaining ---

    @Test
    @DisplayName("Заголовок X-Rate-Limit-Remaining уменьшается с каждым запросом")
    void rateLimitRemainingHeader_ShouldDecrease() throws ServletException, IOException {
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("POST", "/api/auth/login"), response1, filterChain);
        assertThat(response1.getHeader("X-Rate-Limit-Remaining")).isEqualTo("2");

        MockHttpServletResponse response2 = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("POST", "/api/auth/login"), response2, filterChain);
        assertThat(response2.getHeader("X-Rate-Limit-Remaining")).isEqualTo("1");

        MockHttpServletResponse response3 = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("POST", "/api/auth/login"), response3, filterChain);
        assertThat(response3.getHeader("X-Rate-Limit-Remaining")).isEqualTo("0");
    }

    // --- Тест лимита refresh ---

    @Test
    @DisplayName("POST /api/auth/refresh — лимит 5 запросов/мин")
    void refreshEndpoint_ShouldEnforceLimit() throws ServletException, IOException {
        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(createRequest("POST", "/api/auth/refresh"),
                    new MockHttpServletResponse(), filterChain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("POST", "/api/auth/refresh"), response, filterChain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    // --- Тест независимости login и general лимитов ---

    @Test
    @DisplayName("Login и general лимиты независимы")
    void loginAndGeneralLimits_AreIndependent() throws ServletException, IOException {
        // Исчерпываем лимит login
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                    new MockHttpServletResponse(), filterChain);
        }

        // Login заблокирован
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("POST", "/api/auth/login"), loginResponse, filterChain);
        assertThat(loginResponse.getStatus()).isEqualTo(429);

        // General всё ещё доступен
        MockHttpServletResponse generalResponse = new MockHttpServletResponse();
        filter.doFilterInternal(createRequest("GET", "/api/todos/all"), generalResponse, filterChain);
        assertThat(generalResponse.getStatus()).isEqualTo(200);
    }

    // --- Тесты graceful degradation при сбое Redis ---

    @Nested
    @DisplayName("Graceful degradation: Redis BucketProvider бросает RedisCommandTimeoutException")
    class RedisFailureFallback {

        private RateLimitFilter filterWithRedisProvider;
        private BucketProvider failingRedisProvider;
        private BucketProviderInMemory inMemoryFallback;

        @BeforeEach
        void setUpFallbackScenario() {
            RateLimitProperties props = new RateLimitProperties();
            props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
            props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));

            // Эмулируем BucketProviderRedis: resolveBucket возвращает proxy-bucket,
            // у которого tryConsumeAndReturnRemaining() бросает RuntimeException
            // (как Lettuce при сбое Redis).
            failingRedisProvider = mock(BucketProvider.class);
            io.github.bucket4j.Bucket failingBucket = mock(io.github.bucket4j.Bucket.class);
            when(failingBucket.tryConsumeAndReturnRemaining(anyLong()))
                    .thenThrow(new io.lettuce.core.RedisCommandTimeoutException("timeout"));
            when(failingRedisProvider.resolveBucket(anyString(), any()))
                    .thenReturn(failingBucket);

            inMemoryFallback = new BucketProviderInMemory();
            FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
            when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);

            // health=true: circuit breaker не сработает upfront — пойдём в Redis-bucket → catch → fallback
            RedisHealthService health = mock(RedisHealthService.class);
            when(health.isRedisHealthy()).thenReturn(true);
            filterWithRedisProvider = new RateLimitFilter(props, failingRedisProvider, flagStore,
                    inMemoryFallback, health, new SimpleMeterRegistry());
        }

        @Test
        @DisplayName("Redis bucket бросает — fallback на in-memory, запрос проходит (200)")
        void redisThrows_FallsBackToInMemory_RequestPassed() throws ServletException, IOException {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filterWithRedisProvider.doFilterInternal(
                    createRequest("POST", "/api/auth/login"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(any(), any());
            // fallback bucket был создан и использован
            assertThat(inMemoryFallback.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Fallback соблюдает лимит — превышение возвращает 429 даже без Redis")
        void fallback_RespectsLimit_Returns429WhenExceeded() throws ServletException, IOException {
            // Лимит login = 3/мин, исчерпываем через fallback
            for (int i = 0; i < 3; i++) {
                filterWithRedisProvider.doFilterInternal(
                        createRequest("POST", "/api/auth/login"),
                        new MockHttpServletResponse(), filterChain);
            }

            // Следующий запрос — 429 (лимит in-memory bucket'а)
            MockHttpServletResponse response = new MockHttpServletResponse();
            filterWithRedisProvider.doFilterInternal(
                    createRequest("POST", "/api/auth/login"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            verify(filterChain, times(3)).doFilter(any(), any());
        }

        @Test
        @DisplayName("Redis resolveBucket бросает — fallback тоже срабатывает")
        void resolveBucketThrows_FallsBackToInMemory_RequestPassed() throws ServletException, IOException {
            // Эмулируем eager-режим: resolveBucket сам бросает при сбое Redis.
            BucketProvider eagerFailingProvider = mock(BucketProvider.class);
            when(eagerFailingProvider.resolveBucket(anyString(), any()))
                    .thenThrow(new io.lettuce.core.RedisCommandTimeoutException("timeout on getProxy"));

            RateLimitProperties props = new RateLimitProperties();
            props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
            props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
            FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
            when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);
            BucketProviderInMemory localFallback = new BucketProviderInMemory();

            RedisHealthService health = mock(RedisHealthService.class);
            when(health.isRedisHealthy()).thenReturn(true);
            RateLimitFilter eagerFilter = new RateLimitFilter(props, eagerFailingProvider,
                    flagStore, localFallback, health, new SimpleMeterRegistry());

            MockHttpServletResponse response = new MockHttpServletResponse();
            eagerFilter.doFilterInternal(
                    createRequest("POST", "/api/auth/login"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(any(), any());
            assertThat(localFallback.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Без fallback (memory-режим) исключение пробрасывается")
        void noFallback_RethrowsException() {
            FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
            when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);
            RateLimitProperties props = new RateLimitProperties();
            props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
            props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
            // Конструктор: 4-й аргумент null → fallback пустой
            RedisHealthService health = mock(RedisHealthService.class);
            when(health.isRedisHealthy()).thenReturn(true);
            RateLimitFilter filterNoFallback = new RateLimitFilter(props, failingRedisProvider,
                    flagStore, null, health, new SimpleMeterRegistry());

            org.junit.jupiter.api.Assertions.assertThrows(
                    io.lettuce.core.RedisCommandTimeoutException.class,
                    () -> filterNoFallback.doFilterInternal(
                            createRequest("POST", "/api/auth/login"),
                            new MockHttpServletResponse(), filterChain));
        }

        @Test
        @DisplayName("Circuit breaker: health=false — Redis НЕ дёргается, сразу in-memory")
        void circuitBreaker_HealthDown_BypassesRedis() throws ServletException, IOException {
            RateLimitProperties props = new RateLimitProperties();
            props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
            props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
            FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
            when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);
            RedisHealthService healthDown = mock(RedisHealthService.class);
            when(healthDown.isRedisHealthy()).thenReturn(false);
            BucketProviderInMemory localFallback = new BucketProviderInMemory();
            // failingRedisProvider бросает на любой вызов — но не должен вызываться
            // вообще, так как circuit breaker (health=false) сразу идёт в fallback.
            RateLimitFilter cbFilter = new RateLimitFilter(props, failingRedisProvider,
                    flagStore, localFallback, healthDown, new SimpleMeterRegistry());

            MockHttpServletResponse response = new MockHttpServletResponse();
            cbFilter.doFilterInternal(createRequest("POST", "/api/auth/login"), response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(any(), any());
            // Redis-провайдер НЕ должен был быть вызван (circuit breaker отработал upfront)
            verify(failingRedisProvider, never()).resolveBucket(anyString(), any());
            assertThat(localFallback.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Catch-блок: при поимке RedisException вызывается markUnhealthy()")
        void catchBlock_CallsMarkUnhealthy() throws ServletException, IOException {
            RateLimitProperties props = new RateLimitProperties();
            props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
            props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
            FeatureFlagStore flagStore = mock(FeatureFlagStore.class);
            when(flagStore.isEnabled(FeatureFlag.RATE_LIMIT)).thenReturn(true);
            RedisHealthService health = mock(RedisHealthService.class);
            when(health.isRedisHealthy()).thenReturn(true);  // изначально жив

            RateLimitFilter cbFilter = new RateLimitFilter(props, failingRedisProvider,
                    flagStore, inMemoryFallback, health, new SimpleMeterRegistry());

            cbFilter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                    new MockHttpServletResponse(), filterChain);

            // Должен был пометить Redis unhealthy, чтобы следующий запрос не пытался его использовать
            verify(health, atLeastOnce()).markUnhealthy();
        }
    }

    // --- Локализация тела 429-ответа ---

    @Nested
    @DisplayName("429 — локализация message по Accept-Language")
    class TooManyRequestsMessageLocalization {

        private final ObjectMapper json = new ObjectMapper();

        /**
         * Исчерпывает лимит login (3/мин) и возвращает 429-ответ на 4-й запрос
         * с указанным Accept-Language (null — заголовок не отправляется вовсе).
         */
        private MockHttpServletResponse exhaustLoginLimit(String acceptLanguage)
                throws ServletException, IOException {
            for (int i = 0; i < 3; i++) {
                filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                        new MockHttpServletResponse(), filterChain);
            }
            // Заголовок отдаётся через переопределённый getHeader, а не addHeader: сам
            // MockHttpServletRequest на addHeader("Accept-Language", ...) зовёт
            // HttpHeaders.getAcceptLanguageAsLocales(), а тот падает на враждебных значениях
            // вроде "-" — ровно тот баг JDK, который обходит фильтр. Реальный Tomcat заголовок
            // при getHeader() не разбирает, так что стенд ближе к проду, а не дальше.
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login") {
                @Override
                public String getHeader(String name) {
                    return HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(name)
                            ? acceptLanguage
                            : super.getHeader(name);
                }
            };
            request.setRemoteAddr("192.168.1.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertThat(response.getStatus()).isEqualTo(429);
            return response;
        }

        /**
         * Разбирает тело как JSON из СЫРЫХ БАЙТ ответа — так проверяется разом и
         * синтаксическая валидность JSON, и то, что кириллица уехала в UTF-8.
         */
        private JsonNode parseBody(MockHttpServletResponse response) throws IOException {
            return json.readTree(response.getContentAsByteArray());
        }

        @Test
        @DisplayName("Accept-Language: en — message на английском, ровно ожидаемый текст")
        void englishHeader_ReturnsEnglishMessage() throws ServletException, IOException {
            MockHttpServletResponse response = exhaustLoginLimit("en");
            long retryAfter = Long.parseLong(response.getHeader("Retry-After"));

            assertThat(parseBody(response).path("message").asText())
                    .isEqualTo("Too many requests. Retry in " + retryAfter + " sec.");
        }

        @Test
        @DisplayName("Accept-Language: ru — message на русском (регрессия не сломана)")
        void russianHeader_ReturnsRussianMessage() throws ServletException, IOException {
            MockHttpServletResponse response = exhaustLoginLimit("ru");
            long retryAfter = Long.parseLong(response.getHeader("Retry-After"));

            assertThat(parseBody(response).path("message").asText())
                    .isEqualTo("Слишком много запросов. Повторите через " + retryAfter + " сек.");
        }

        @Test
        @DisplayName("Без Accept-Language — русский (дефолт сервера)")
        void noHeader_ReturnsRussianMessage() throws ServletException, IOException {
            MockHttpServletResponse response = exhaustLoginLimit(null);

            assertThat(parseBody(response).path("message").asText())
                    .startsWith("Слишком много запросов");
        }

        @Test
        @DisplayName("Машиночитаемые поля не зависят от локали")
        void machineReadableFields_AreLocaleIndependent() throws ServletException, IOException {
            for (String header : new String[]{"en", "ru", "de"}) {
                MockHttpServletResponse response = exhaustLoginLimit(header);
                JsonNode body = parseBody(response);

                assertThat(body.path("error").asText()).isEqualTo("Too Many Requests");
                assertThat(body.path("retryAfter").isNumber()).isTrue();
                assertThat(body.path("retryAfter").asLong())
                        .isEqualTo(Long.parseLong(response.getHeader("Retry-After")));
                assertThat(response.getContentType()).startsWith("application/json");
                assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
            }
        }

        @Test
        @DisplayName("Враждебный заголовок \"-\" не роняет ответ (JDK LanguageRange бросал на нём AIOOBE)")
        void hostileHeader_DoesNotBreakResponse() throws ServletException, IOException {
            MockHttpServletResponse response = exhaustLoginLimit("-");

            assertThat(parseBody(response).path("message").asText())
                    .startsWith("Слишком много запросов");
        }
    }

    // --- Разбор Accept-Language (прямые вызовы resolveMessageLanguage) ---

    @Nested
    @DisplayName("resolveMessageLanguage — выбор языка по Accept-Language")
    class MessageLanguageResolution {

        @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
        @CsvSource(nullValues = "NULL", value = {
                // Явные и региональные теги
                "NULL,                      ru",
                "en,                        en",
                "ru,                        ru",
                "EN,                        en",
                "en-GB,                     en",
                "ru-RU,                     ru",
                // Полные браузерные заголовки: первый range и q-веса согласованы
                "'en-US,en;q=0.9,ru;q=0.8', en",
                "'ru-RU,ru;q=0.9,en;q=0.8', ru",
                // q-веса РАСХОДЯТСЯ с порядком — единственные кейсы, отличающие
                // разбор весов от наивного "берём первый range"
                "'ru;q=0.3,en;q=0.9',       en",
                "'en;q=0.3,ru;q=0.9',       ru",
                // q=0 по RFC 9110 — "неприемлемо", такой язык не выбираем
                "'en;q=0',                  ru",
                "'en;q=0,de',               ru",
                "'ru;q=0,en;q=0.5',         en",
                // Битый q — элемент игнорируется, разбор не падает
                "'en;q=abc',                ru",
                // Неподдерживаемые и мусорные заголовки → дефолт сервера
                "'de-DE,de;q=0.9',          ru",
                "'###',                     ru",
                "'*',                       ru",
                "'*;q=1,en;q=0.5',          en",
                // Входы, на которых JDK LanguageRange.parse бросал AIOOBE
                "'-',                       ru",
                "'--',                      ru",
                "'ru,-',                    ru",
                "'-,en',                    en",
                // Один битый элемент не должен обнулять валидные соседние
                "'en, ru_RU',               en"
        })
        void resolvesLanguage(String header, String expected) {
            assertThat(filter.resolveMessageLanguage(header)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Пустой заголовок и заголовок из пробелов — дефолт сервера")
        void blankHeaders_FallBackToDefault() {
            assertThat(filter.resolveMessageLanguage("")).isEqualTo("ru");
            assertThat(filter.resolveMessageLanguage("   ")).isEqualTo("ru");
        }

        @Test
        @DisplayName("Заголовок 8 КБ разбирается за ограниченное время (DoS на ветке 429)")
        void hugeHostileHeader_IsResolvedInBoundedTime() {
            // "de-*-*-*-…" на 8 КБ (дефолтный лимит размера заголовка в Tomcat).
            // Через Locale.lookupTag такой вход стоил ~6 секунд CPU на КАЖДЫЙ 429-ответ —
            // то есть уже отшитый лимитом клиент мог заказывать себе секунды процессора.
            String hostile = "de" + "-*".repeat(4000);

            long startNanos = System.nanoTime();
            String lang = filter.resolveMessageLanguage(hostile);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(lang).isEqualTo("ru");
            // Запас к реальному времени разбора — три порядка: порог ловит возврат
            // к квадратичному regex-разбору, а не медленный CI.
            assertThat(elapsedMillis).isLessThan(500L);
        }

        @Test
        @DisplayName("Число разбираемых элементов ограничено — хвост длинного заголовка игнорируется")
        void rangeCount_IsCapped() {
            // Осознанный компромисс: реальные клиенты присылают единицы языков,
            // а тысячи range'ей — признак атаки, а не браузера.
            String flood = "de-de,".repeat(1300) + "en";

            assertThat(filter.resolveMessageLanguage(flood)).isEqualTo("ru");
        }
    }
}
