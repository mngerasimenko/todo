package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Фильтр rate limiting на основе Bucket4j (алгоритм token bucket).
 * Применяет разные лимиты для auth-эндпоинтов и общих запросов.
 * Ключ — IP-адрес клиента (из X-Forwarded-For за nginx).
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;

    /** Хранилище bucket'ов: ключ = "тип:IP" */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Время последнего использования bucket'а для очистки */
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Rate limiting отключён — пропускаем все запросы
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Rate limiting применяется только к /api/** запросам
        if (!uri.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String bucketKey = resolveBucketKey(uri, request.getMethod(), clientIp);

        // Для эндпоинтов без ограничений (status, swagger и т.д.) — пропускаем
        if (bucketKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(uri, request.getMethod()));
        lastAccessTime.put(bucketKey, System.currentTimeMillis());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000 + 1;
            log.warn("Rate limit превышен для IP={}, эндпоинт={}, повтор через {} сек",
                    clientIp, uri, retryAfterSeconds);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setHeader("X-Rate-Limit-Remaining", "0");
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"message\":\"Слишком много запросов. Повторите через "
                            + retryAfterSeconds + " сек.\",\"retryAfter\":" + retryAfterSeconds + "}"
            );
        }
    }

    /**
     * Определяет IP-адрес клиента с учётом nginx reverse proxy.
     */
    String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Берём первый IP (реальный клиент), остальные — промежуточные прокси
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Формирует ключ bucket'а на основе URI и IP.
     * Возвращает null для эндпоинтов без ограничений.
     */
    String resolveBucketKey(String uri, String method, String clientIp) {
        if ("POST".equalsIgnoreCase(method)) {
            if (uri.equals("/api/auth/login")) {
                return "login:" + clientIp;
            }
            if (uri.equals("/api/auth/register")) {
                return "register:" + clientIp;
            }
            if (uri.equals("/api/auth/refresh")) {
                return "refresh:" + clientIp;
            }
            if (uri.equals("/api/auth/forgot-password")) {
                return "forgotPassword:" + clientIp;
            }
            if (uri.equals("/api/auth/verify-email")) {
                return "verifyEmail:" + clientIp;
            }
            if (uri.equals("/api/auth/reset-password")) {
                return "resetPassword:" + clientIp;
            }
            if (uri.equals("/api/auth/resend-verification")) {
                return "resendVerification:" + clientIp;
            }
            if (uri.equals("/api/auth/change-email")) {
                return "changeEmail:" + clientIp;
            }
        }

        // Пропускаем статус, swagger, api-docs без лимитов
        if (uri.equals("/api/status") || uri.equals("/api/appName")
                || uri.startsWith("/api/v3/api-docs") || uri.startsWith("/api/swagger-ui")) {
            return null;
        }

        return "general:" + clientIp;
    }

    /**
     * Создаёт bucket с лимитами согласно типу эндпоинта.
     */
    private Bucket createBucket(String uri, String method) {
        RateLimitProperties.EndpointLimit limit = resolveLimit(uri, method);
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.getRequests())
                .refillGreedy(limit.getRequests(), Duration.ofSeconds(limit.getDurationSeconds()))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private RateLimitProperties.EndpointLimit resolveLimit(String uri, String method) {
        if ("POST".equalsIgnoreCase(method)) {
            if (uri.equals("/api/auth/login")) return properties.getLogin();
            if (uri.equals("/api/auth/register")) return properties.getRegister();
            if (uri.equals("/api/auth/refresh")) return properties.getRefresh();
            if (uri.equals("/api/auth/forgot-password")) return properties.getForgotPassword();
            if (uri.equals("/api/auth/verify-email")) return properties.getVerifyEmail();
            if (uri.equals("/api/auth/reset-password")) return properties.getResetPassword();
            if (uri.equals("/api/auth/resend-verification")) return properties.getResendVerification();
            if (uri.equals("/api/auth/change-email")) return properties.getChangeEmail();
        }
        return properties.getGeneral();
    }

    /**
     * Удаляет bucket'ы, которые не использовались дольше указанного времени.
     * Вызывается из планировщика для предотвращения утечки памяти.
     */
    public void evictExpiredBuckets(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        lastAccessTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() >= maxIdleMillis) {
                buckets.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Количество активных bucket'ов (для мониторинга и тестов).
     */
    public int getActiveBucketCount() {
        return buckets.size();
    }
}
