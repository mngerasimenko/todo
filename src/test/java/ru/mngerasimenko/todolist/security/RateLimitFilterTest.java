package ru.mngerasimenko.todolist.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для RateLimitFilter.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setLogin(new RateLimitProperties.EndpointLimit(3, 60));
        props.setRegister(new RateLimitProperties.EndpointLimit(2, 3600));
        props.setRefresh(new RateLimitProperties.EndpointLimit(5, 60));
        props.setGeneral(new RateLimitProperties.EndpointLimit(10, 60));
        filter = new RateLimitFilter(props);
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

            // 5 запросов на /api/users/all
            for (int i = 0; i < 5; i++) {
                filter.doFilterInternal(createRequest("GET", "/api/users/all"),
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
        @DisplayName("GET /api/todos/all → general:IP")
        void generalEndpoint() {
            String key = filter.resolveBucketKey("/api/todos/all", "GET", "1.2.3.4");
            assertThat(key).isEqualTo("general:1.2.3.4");
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

            assertThat(filter.getActiveBucketCount()).isEqualTo(1);

            // Очистка с нулевым временем жизни — удалит всё
            filter.evictExpiredBuckets(0);

            assertThat(filter.getActiveBucketCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("evictExpiredBuckets не удаляет свежие bucket'ы")
        void evictExpiredBuckets_ShouldKeepRecentBuckets() throws ServletException, IOException {
            filter.doFilterInternal(createRequest("POST", "/api/auth/login"),
                    new MockHttpServletResponse(), filterChain);

            // Очистка с большим временем жизни — не удалит
            filter.evictExpiredBuckets(Long.MAX_VALUE);

            assertThat(filter.getActiveBucketCount()).isEqualTo(1);
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
}
