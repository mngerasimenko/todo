package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.service.RedisHealthService;
import ru.mngerasimenko.todolist.util.AcceptLanguageParser;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Шаблоны человекочитаемого {@code message} в 429-ответе: язык → текст с {@code %d} секунд.
     * {@link #resolveMessageLanguage} выбирает язык из ключей этой карты, {@link #buildTooManyRequestsBody}
     * берёт отсюда текст. Набор языков и {@link #DEFAULT_MESSAGE_LANG} держать равными
     * {@code SUPPORTED_PAGE_LANGUAGES} и {@code DEFAULT_PAGE_LANGUAGE} в {@code EmailUnsubscribeController}:
     * иначе один и тот же заголовок даст 429 и страницу отписки на разных языках.
     * <p>
     * Осознанное исключение из политики {@code I18nConfig} («REST API не локализуется»): фильтр
     * отрабатывает до Spring MVC и отдаёт тело сам, минуя {@code GlobalExceptionHandler}, поэтому
     * его сообщение исторически было русским для всех. Строки лежат здесь, а не в {@code MessageSource}:
     * тянуть его в security-цепочку ради двух строк не нужно.
     */
    private static final Map<String, String> MESSAGE_TEMPLATES = Map.of(
            "ru", "Слишком много запросов. Повторите через %d сек.",
            "en", "Too many requests. Retry in %d sec.");

    /** Язык 429-сообщения по умолчанию — дефолтная локаль сервера (см. {@code I18nConfig}). */
    private static final String DEFAULT_MESSAGE_LANG = "ru";

    private final RateLimitProperties properties;
    private final BucketProvider bucketProvider;
    private final FeatureFlagStore flagStore;
    private final RedisHealthService redisHealthService;
    private final MeterRegistry meterRegistry;

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
                           @Autowired(required = false) BucketProviderInMemory inMemoryFallback,
                           RedisHealthService redisHealthService,
                           MeterRegistry meterRegistry) {
        this.properties = properties;
        this.bucketProvider = bucketProvider;
        this.flagStore = flagStore;
        this.redisHealthService = redisHealthService;
        this.meterRegistry = meterRegistry;
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
            response.getWriter().write(buildTooManyRequestsBody(acceptLanguage(request), retryAfterSeconds));
        }
    }

    /**
     * Все строки {@code Accept-Language}, склеенные через запятую. По RFC 9110 несколько строк
     * заголовка равны одной, и ровно так их получают контроллеры через {@code @RequestHeader String}:
     * {@code getHeader} отдал бы только первую строку, и на запросе «de» + «en» язык 429 разошёлся бы
     * со страницей отписки. Вызывается только на ветке 429.
     */
    private static String acceptLanguage(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.ACCEPT_LANGUAGE);
        return values == null ? null : String.join(",", Collections.list(values));
    }

    /**
     * Собирает JSON-тело 429-ответа с локализованным {@code message}.
     * Машиночитаемые поля ({@code error}, {@code retryAfter}) не локализуются. Там, где клиенты
     * показывают серверную ошибку, это {@code message}, поэтому текст тоже часть контракта.
     * В тело попадают только константы из {@link #MESSAGE_TEMPLATES} и число секунд;
     * пользовательский ввод в JSON не течёт, поэтому экранирование не требуется.
     */
    String buildTooManyRequestsBody(String acceptLanguage, long retryAfterSeconds) {
        String template = MESSAGE_TEMPLATES.get(resolveMessageLanguage(acceptLanguage));
        String message = String.format(Locale.ROOT, template, retryAfterSeconds);
        return "{\"error\":\"Too Many Requests\",\"message\":\"" + message
                + "\",\"retryAfter\":" + retryAfterSeconds + "}";
    }

    /**
     * Выбирает язык сообщения по {@code Accept-Language}: поддерживаемый язык с наибольшим
     * q-весом ("ru;q=0.3,en;q=0.9" → "en", "en-GB" → "en"). {@link #DEFAULT_MESSAGE_LANG} —
     * если ни один поддерживаемый язык не приемлем: заголовка нет, он битый, в нём только
     * неподдерживаемые языки или {@code q=0}. Wildcard {@code *} языком не считается и не выбирается.
     * <p>
     * Разбор — общий {@link AcceptLanguageParser}, тот же, что выбирает язык страницы отписки:
     * один заголовок не должен давать 429 и страницу на разных языках. Язык писем при регистрации
     * берётся тем же разбором, но без сведения к поддерживаемым языкам.
     * Заголовок читается только на ветке 429, куда отшитый лимитом клиент попадает намеренно, —
     * поэтому разбор обязан быть линейным и не бросать исключений; парсер это гарантирует.
     * <p>
     * Package-private для unit-тестирования.
     */
    String resolveMessageLanguage(String acceptLanguage) {
        return AcceptLanguageParser.bestSupportedLanguage(
                acceptLanguage, MESSAGE_TEMPLATES.keySet(), DEFAULT_MESSAGE_LANG);
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
            if (uri.equals("/api/auth/change-password")) {
                return "changePassword:" + clientIp;
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

        // Публичный GET /api/suggestions/all — bulk-выгрузка словаря (Server R-7).
        // Отдельный, более строгий bucket: ответ тяжелее (весь словарь), зовётся редко (~1/сутки).
        if ("GET".equalsIgnoreCase(method) && uri.equals("/api/suggestions/all")) {
            return "suggestions-all:" + clientIp;
        }

        // Публичный GET /api/suggestions — отдельный bucket, иначе анонимные
        // клиенты будут есть general-бюджет авторизованного пользователя по тому же IP.
        if ("GET".equalsIgnoreCase(method) && uri.equals("/api/suggestions")) {
            return "suggestions:" + clientIp;
        }

        // Fail-safe: любой прочий путь под /api/suggestions/ — в (более строгий) suggestions-bucket,
        // а НЕ в general. Exact-сравнение выше использует raw URI, тогда как роутинг Spring нормализует
        // путь — если нормализация когда-нибудь пропустит вариант, которого exact не поймал, он не должен
        // утекать в более щедрый general (100/60). Сейчас StrictHttpFirewall закрывает такие варианты,
        // но защита fail-safe не должна на это полагаться.
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/suggestions/")) {
            return "suggestions:" + clientIp;
        }

        return "general:" + clientIp;
    }

    /**
     * Резолвит bucket через основной {@link BucketProvider} с защитой от сбоев Redis.
     *
     * Двухуровневая защита:
     *   1. Circuit breaker: если {@link RedisHealthService} уже знает что Redis down,
     *      сразу идём в in-memory без попытки Lettuce-запроса (минус 300мс timeout).
     *   2. Try/catch: если circuit breaker ещё не сработал, ловим исключение,
     *      помечаем Redis unhealthy для следующих запросов, и переключаемся на in-memory.
     *
     * В текущей версии bucket4j-lettuce {@code LettuceBasedProxyManager.getProxy(...)}
     * ленивый и Redis-вызов не делает — но это implementation detail. Защита bulletproof
     * на случай будущих изменений.
     */
    Bucket resolveBucketWithFallback(String bucketKey, BucketConfiguration config) {
        if (inMemoryFallback.isPresent() && !redisHealthService.isRedisHealthy()) {
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "rate_limit");
            return inMemoryFallback.get().resolveBucket(bucketKey, config);
        }
        try {
            return bucketProvider.resolveBucket(bucketKey, config);
        } catch (RuntimeException ex) {
            if (inMemoryFallback.isEmpty()) {
                throw ex;
            }
            log.warn("Rate-limit Redis недоступен на resolveBucket, fallback in-memory для key={}: {}",
                    bucketKey, ex.toString());
            redisHealthService.markUnhealthy();
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "rate_limit");
            return inMemoryFallback.get().resolveBucket(bucketKey, config);
        }
    }

    /**
     * Дёргает {@code bucket.tryConsumeAndReturnRemaining(1)} с защитой от сбоев Redis.
     *
     * Двухуровневая защита (см. {@link #resolveBucketWithFallback}):
     *   1. Если circuit breaker уже знает что Redis down — резолвим in-memory bucket
     *      и работаем с ним.
     *   2. Иначе пробуем основной bucket; при ошибке — markUnhealthy + fallback.
     *
     * Если fallback недоступен (memory-режим — основной провайдер сам in-memory,
     * никаких дополнительных fallback'ов нет), exception пробрасывается дальше.
     */
    ConsumptionProbe tryConsumeWithFallback(Bucket bucket, String bucketKey, BucketConfiguration config) {
        if (inMemoryFallback.isPresent() && !redisHealthService.isRedisHealthy()) {
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "rate_limit");
            Bucket memBucket = inMemoryFallback.get().resolveBucket(bucketKey, config);
            return memBucket.tryConsumeAndReturnRemaining(1);
        }
        try {
            return bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException ex) {
            if (inMemoryFallback.isEmpty()) {
                throw ex;
            }
            log.warn("Rate-limit Redis недоступен, fallback in-memory для key={}: {}", bucketKey, ex.toString());
            redisHealthService.markUnhealthy();
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "rate_limit");
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
            if (uri.equals("/api/auth/change-password")) return properties.getChangePassword();
            if (uri.equals("/api/auth/logout")) return properties.getLogout();
        }
        if ("GET".equalsIgnoreCase(method) && uri.equals("/api/suggestions/all")) {
            return properties.getSuggestionsBulk();
        }
        if ("GET".equalsIgnoreCase(method) && uri.equals("/api/suggestions")) {
            return properties.getSuggestions();
        }
        // Fail-safe (см. resolveBucketKey): прочие /api/suggestions/ берут лимит suggestions, не general.
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/suggestions/")) {
            return properties.getSuggestions();
        }
        return properties.getGeneral();
    }

}
