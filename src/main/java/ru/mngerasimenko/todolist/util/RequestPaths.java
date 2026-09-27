package ru.mngerasimenko.todolist.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Путь запроса внутри приложения — тот, по которому его маршрутизируют MVC и Spring Security.
 * <p>
 * Проверки по пути (rate-limit, маскировка {@code /api/admin}) обязаны брать его отсюда, а не из
 * {@code getRequestURI()}. Сырой URI расходится с маршрутизацией двумя способами, и оба давали
 * подбор пароля без лимита (замеры на стейдже 27.09):
 * <ul>
 *   <li>при {@code server.forward-headers-strategy=framework} перед нашими фильтрами стоит
 *   {@code ForwardedHeaderFilter}, и клиентский {@code X-Forwarded-Prefix} дописывается в начало
 *   URI — {@code /x/api/auth/login} не начинается с {@code /api/}. Обёртка отдаёт тот же префикс
 *   как {@code getContextPath()}, поэтому отрезание contextPath его убирает;</li>
 *   <li>процент-кодированные буквы ({@code /%61pi/auth/login}): {@code StrictHttpFirewall} их
 *   пропускает, MVC сопоставляет уже декодированный путь, а сырой с {@code /api/} не совпадал.</li>
 * </ul>
 */
public final class RequestPaths {

    /** Декодирует и чистит путь так же, как его сопоставляет MVC. Настраивается один раз — дальше только чтение. */
    private static final UrlPathHelper DECODING = new UrlPathHelper();

    static {
        DECODING.setUrlDecode(true);
        DECODING.setRemoveSemicolonContent(true);
    }

    private RequestPaths() {
    }

    /** Декодированный путь внутри приложения — для решений (лимиты, маскировка). */
    public static String pathWithinApplication(HttpServletRequest request) {
        try {
            return DECODING.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            // Битая %-последовательность. В самом URI её отбивает Tomcat, но в клиентском
            // X-Forwarded-Prefix она доезжает (Tomcat заголовки не проверяет). Обхода это не
            // открывает: MVC разбирает путь вместе с префиксом и падает на той же последовательности,
            // до контроллера запрос не доходит. На проде префикс вычищает nginx.
            return rawPathWithinApplication(request);
        }
    }

    /**
     * Сырой, недекодированный путь внутри приложения — для строки лога: кодирование в нём само
     * по себе улика, декодирование её бы стёрло. Для решений не годится (см. javadoc класса).
     */
    public static String rawPathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || contextPath == null || contextPath.isEmpty() || !uri.startsWith(contextPath)) {
            return uri;
        }
        return uri.substring(contextPath.length());
    }
}
