package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Фильтр rate limiting на основе Bucket4j (алгоритм token bucket).
 * Применяет разные лимиты для auth-эндпоинтов и общих запросов.
 * Ключ — IP-адрес клиента (из X-Real-IP за nginx).
 * Хранилище bucket'ов абстрагировано через BucketProvider (memory или redis).
 * Включение/выключение — через {@link FeatureFlag#RATE_LIMIT} (runtime + env).
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final BucketProvider bucketProvider;
    private final FeatureFlagStore flagStore;

    /**
     * In-memory fallback для случая, когда основной {@link BucketProvider} — Redis,
     * и он становится недоступен. Активен только в Redis-режиме (создаётся через
     * {@code BucketRedisConfig.bucket4jInMemoryFallback}). В memory-режиме отсутствует —
     * там основной провайдер сам in-memory, fallback не нужен.
     */
    private final Optional<BucketProviderInMemory> inMemoryFallback;

    public RateLimitFilter(RateLimitProperties properties,
                           BucketProvider bucketProvider,
                           FeatureFlagStore flagStore,
                           @Autowired(required = false) BucketProviderInMemory inMemoryFallback) {
        this.properties = properties;
        this.bucketProvider = bucketProvider;
        this.flagStore = flagStore;
        // В memory-режиме основной провайдер сам in-memory — fallback не нужен (передаём пустой Optional).
        // В redis-режиме сюда инжектится отдельный bean из BucketRedisConfig.
        this.inMemoryFallback = (bucketProvider instanceof BucketProviderInMemory)
                ? Optional.empty()
                : Optional.ofNullable(inMemoryFallback);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Rate limiting отключён — пропускаем все запросы
        if (!flagStore.isEnabled(FeatureFlag.RATE_LIMIT)) {
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

        BucketConfiguration config = buildConfiguration(uri, request.getMethod());
        Bucket bucket = resolveBucketWithFallback(bucketKey, config);
        ConsumptionProbe probe = tryConsumeWithFallback(bucket, bucketKey, config);

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
     * Определяет IP-адрес клиента через доверенный заголовок от nginx reverse proxy.
     * Использует X-Real-IP (одно значение, задаётся nginx), а не X-Forwarded-For (спуфится клиентом).
     */
    String resolveClientIp(HttpServletRequest request) {
        String header = properties.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank()) {
                return ip.trim();
            }
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
            if (uri.equals("/api/auth/logout")) {
                return "logout:" + clientIp;
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
     * Резолвит bucket через основной {@link BucketProvider} с защитой от сбоев Redis.
     *
     * В текущей версии bucket4j-lettuce {@code LettuceBasedProxyManager.getProxy(...)}
     * ленивый и Redis-вызов не делает — но это implementation detail. Если будущая
     * версия начнёт делать eager-вызов на этом этапе, ошибка должна быть поймана
     * и обработана так же, как на {@link #tryConsumeWithFallback}.
     */
    Bucket resolveBucketWithFallback(String bucketKey, BucketConfiguration config) {
        try {
            return bucketProvider.resolveBucket(bucketKey, config);
        } catch (RuntimeException ex) {
            if (inMemoryFallback.isEmpty()) {
                throw ex;
            }
            log.warn("Rate-limit Redis недоступен на resolveBucket, fallback in-memory для key={}: {}",
                    bucketKey, ex.toString());
            return inMemoryFallback.get().resolveBucket(bucketKey, config);
        }
    }

    /**
     * Дёргает {@code bucket.tryConsumeAndReturnRemaining(1)} с защитой от сбоев Redis.
     *
     * Если основной bucket — Redis, и Lettuce бросает {@code RedisCommandTimeoutException}
     * (или любую другую RuntimeException), переключаемся на in-memory fallback bucket
     * с тем же ключом и конфигурацией. Лимит продолжает работать локально в JVM
     * до восстановления Redis. Без этой защиты каждый запрос висел бы 60 сек
     * (дефолт Lettuce) и возвращал HTTP 500.
     *
     * Если fallback недоступен (memory-режим — основной провайдер сам in-memory,
     * никаких дополнительных fallback'ов нет), exception пробрасывается дальше:
     * это означает баг в самом in-memory bucket'е, а не сбой Redis.
     */
    ConsumptionProbe tryConsumeWithFallback(Bucket bucket, String bucketKey, BucketConfiguration config) {
        try {
            return bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException ex) {
            if (inMemoryFallback.isEmpty()) {
                throw ex;
            }
            log.warn("Rate-limit Redis недоступен, fallback in-memory для key={}: {}", bucketKey, ex.toString());
            Bucket memBucket = inMemoryFallback.get().resolveBucket(bucketKey, config);
            return memBucket.tryConsumeAndReturnRemaining(1);
        }
    }

    private BucketConfiguration buildConfiguration(String uri, String method) {
        RateLimitProperties.EndpointLimit limit = resolveLimit(uri, method);
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.getRequests())
                .refillGreedy(limit.getRequests(), Duration.ofSeconds(limit.getDurationSeconds()))
                .build();
        return BucketConfiguration.builder().addLimit(bandwidth).build();
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
            if (uri.equals("/api/auth/logout")) return properties.getLogout();
        }
        return properties.getGeneral();
    }

}
