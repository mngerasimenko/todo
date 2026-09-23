package ru.mngerasimenko.todolist.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * <p>
 * <b>Фильтр стоит снаружи security-цепочки.</b> Без {@link Order} Spring Boot регистрирует
 * {@code @Component}-фильтр с наименьшим приоритетом, то есть ВНУТРИ цепочки Spring Security
 * (её порядок −100), и тогда всё, что отбито до контроллера, в лог не попадает вовсе:
 * замер на стейдже 23.09 — запрос с битым JWT вернул 401 и не оставил ни одной строки.
 * Ровно этот трафик (401-штормы, 429) интереснее всего привязывать к версии клиента, поэтому
 * фильтр поднят наружу. Побочный эффект: {@code duration} теперь включает rate-limit и разбор
 * JWT — это ближе к тому, что видит клиент.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoggingFilter extends OncePerRequestFilter {

    /** Заголовок с versionCode приложения. */
    private static final String HEADER_APP_VERSION = "X-App-Version";

    /** Заголовок с платформой клиента. */
    private static final String HEADER_APP_PLATFORM = "X-App-Platform";

    /**
     * Потолок длины на каждую половину. Длину строки лога не должен задавать клиент:
     * заголовок в 8 КБ (дефолтный предел Tomcat) иначе раздул бы каждую запись.
     */
    private static final int MAX_VALUE_LENGTH = 16;

    /**
     * Потолок длины URI. Он тоже приходит от клиента, причём на публичных путях
     * ({@code /api/swagger-ui/**}) — без авторизации и без rate-limit. Семикилобайтный путь
     * в каждой строке прокручивает всю тридцатидневную историю логов за минуты, и вместе с
     * ней уезжают следы того, кто это сделал.
     */
    private static final int MAX_URI_LENGTH = 256;

    /** Чем заменяем отсутствующее значение. */
    private static final String ABSENT = "-";

    /**
     * Чем заменяем значение, которое пришло, но негодно. Отдельный знак, а не чистка с
     * выбрасыванием символов: склейка остатка превращает {@code "29, 30"} (так прокси
     * объединяет два одноимённых заголовка) в {@code "2930"} — несуществующую версию,
     * неотличимую от настоящей ни глазом, ни скриптом. И «прислал мусор» не должно
     * выглядеть как «не прислал ничего».
     */
    private static final String MANGLED = "?";

    /** Знак обрезки: видно, что строку урезали мы, а не клиент прислал её такой. */
    private static final String ELLIPSIS = "…";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        try {
            // Продолжить обработку запроса
            filterChain.doFilter(request, response);
        } finally {
            // finally, а не последовательно: обрыв соединения при записи ответа и любое
            // исключение из цепочки иначе уносят строку лога — то есть ровно аварийный случай.
            long duration = System.currentTimeMillis() - startTime;
            logger.info(String.format(
                    "request method: %s, request URI: %s, response status: %d, request processing time: %d ms, app: %s",
                    request.getMethod(), shorten(request.getRequestURI()), response.getStatus(), duration, appTag(request)));
        }
    }

    /**
     * {@code <платформа>/<версия>}: {@code -} — заголовка нет, {@code ?} — пришёл негодным.
     * Форма всегда одна, из двух половин: разборщику лога не нужно знать про особый случай.
     */
    private static String appTag(HttpServletRequest request) {
        return valueOf(request.getHeader(HEADER_APP_PLATFORM)) + "/" + valueOf(request.getHeader(HEADER_APP_VERSION));
    }

    /** Заголовок как есть, если он целиком допустим; иначе прочерк или знак негодности. */
    private static String valueOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ABSENT;
        }
        if (raw.length() > MAX_VALUE_LENGTH) {
            return MANGLED;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = c == '.' || c == '-' || c == '_' || (c < 128 && Character.isLetterOrDigit(c));
            if (!allowed) {
                return MANGLED;
            }
        }
        return raw;
    }

    /** Обрезает длинный URI, не разрывая суррогатную пару. */
    private static String shorten(String uri) {
        if (uri == null) {
            return ABSENT;
        }
        if (uri.length() <= MAX_URI_LENGTH) {
            return uri;
        }
        int cut = MAX_URI_LENGTH;
        if (Character.isHighSurrogate(uri.charAt(cut - 1))) {
            cut--;
        }
        return uri.substring(0, cut) + ELLIPSIS;
    }
}
