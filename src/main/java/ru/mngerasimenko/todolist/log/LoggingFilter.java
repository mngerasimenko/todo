package ru.mngerasimenko.todolist.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP-фильтр для логирования запросов (метод, URI, статус, время выполнения, версия клиента).
 * <p>
 * Версия и платформа приходят заголовками {@code X-App-Version} / {@code X-App-Platform} —
 * до них строку лога нельзя было привязать к релизу приложения, и версии различали косвенно,
 * по наличию {@code client_request_id}. Значение полностью под контролем клиента: оно только
 * логируется, никакой логики на нём не строится.
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    /** Заголовок с versionCode приложения. */
    private static final String HEADER_APP_VERSION = "X-App-Version";

    /** Заголовок с платформой клиента. */
    private static final String HEADER_APP_PLATFORM = "X-App-Platform";

    /**
     * Потолок длины на каждую половину. Длину строки лога не должен задавать клиент:
     * заголовок в 8 КБ (дефолтный предел Tomcat) иначе раздул бы каждую строку.
     */
    private static final int MAX_VALUE_LENGTH = 16;

    /** Чем заменяем отсутствующее или целиком негодное значение. */
    private static final String ABSENT = "-";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        // Продолжить обработку запроса
        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - startTime;

        String logMessage = String.format("request method: %s, request URI: %s, response status: %d, request processing time: %d ms, app: %s",
                request.getMethod(), request.getRequestURI(), response.getStatus(), duration, appTag(request));
        logger.info(logMessage);
    }

    /**
     * {@code <платформа>/<версия>}, отсутствующая половина — прочерк; нет ни одной — просто прочерк.
     */
    private static String appTag(HttpServletRequest request) {
        String platform = sanitize(request.getHeader(HEADER_APP_PLATFORM));
        String version = sanitize(request.getHeader(HEADER_APP_VERSION));
        if (platform == null && version == null) {
            return ABSENT;
        }
        return (platform == null ? ABSENT : platform) + "/" + (version == null ? ABSENT : version);
    }

    /**
     * Оставляет только латиницу, цифры, точку, дефис и подчёркивание, обрезая до
     * {@link #MAX_VALUE_LENGTH}. Символы не экранируются, а выбрасываются: перевод строки в
     * заголовке иначе дорисовал бы в лог поддельную строку, неотличимую от настоящей, а
     * экранирование оставило бы её читаемой глазами и всё равно сбивало бы разбор лога.
     * Значение, от которого после чистки ничего не осталось, равно отсутствующему.
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(MAX_VALUE_LENGTH);
        for (int i = 0; i < raw.length() && cleaned.length() < MAX_VALUE_LENGTH; i++) {
            char c = raw.charAt(i);
            boolean allowed = c == '.' || c == '-' || c == '_'
                    || (c < 128 && Character.isLetterOrDigit(c));
            if (allowed) {
                cleaned.append(c);
            }
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }
}
