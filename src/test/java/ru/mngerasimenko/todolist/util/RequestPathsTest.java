package ru.mngerasimenko.todolist.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Путь запроса внутри приложения — тот, по которому его маршрутизируют MVC и Spring Security.
 * <p>
 * При {@code server.forward-headers-strategy=framework} перед нашими фильтрами стоит
 * {@link ForwardedHeaderFilter}, и заголовок {@code X-Forwarded-Prefix} от клиента дописывается в
 * начало {@code getRequestURI()}. Маршрутизации это не мешает, а проверки по {@code getRequestURI()}
 * обходит: {@code /x/api/auth/login} не начинается с {@code /api/} — замер на стейдже 27.09.
 */
class RequestPathsTest {

    @Test
    @DisplayName("Префикс из X-Forwarded-Prefix в путь не попадает")
    void forwardedPrefix_IsNotPartOfPath() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/auth/login");
        raw.addHeader("X-Forwarded-Prefix", "/x");

        HttpServletRequest wrapped = throughForwardedHeaderFilter(raw);

        assertThat(wrapped.getRequestURI()).as("предпосылка: обёртка дописывает префикс").isEqualTo("/x/api/auth/login");
        assertThat(RequestPaths.pathWithinApplication(wrapped)).isEqualTo("/api/auth/login");
    }

    @Test
    @DisplayName("Процент-кодированные буквы декодируются: MVC маршрутизирует /%61pi/… как /api/…")
    void percentEncodedLetters_AreDecoded() {
        // StrictHttpFirewall отбивает только %2F, %2E, %25, %3B и подобные, а закодированные буквы
        // пропускает. Сырой путь не начинался с /api/, и лимит на вход не применялся вовсе
        // (замер на стейдже 27.09: /%61pi/auth/login дошёл до контроллера без X-Rate-Limit-Remaining).
        assertThat(RequestPaths.pathWithinApplication(new MockHttpServletRequest("POST", "/%61pi/auth/login")))
                .isEqualTo("/api/auth/login");
        assertThat(RequestPaths.pathWithinApplication(new MockHttpServletRequest("POST", "/api/auth/%6Cogin")))
                .isEqualTo("/api/auth/login");
    }

    @Test
    @DisplayName("Сырой путь для лога не декодируется: кодирование — улика, её не стираем")
    void rawPath_KeepsEncoding() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/%61pi/auth/login");
        raw.addHeader("X-Forwarded-Prefix", "/x");

        assertThat(RequestPaths.rawPathWithinApplication(throughForwardedHeaderFilter(raw)))
                .isEqualTo("/%61pi/auth/login");
    }

    @Test
    @DisplayName("Битое кодирование не роняет запрос")
    void malformedEncoding_DoesNotThrow() {
        assertThat(RequestPaths.pathWithinApplication(new MockHttpServletRequest("GET", "/api/%zz"))).isNotNull();
    }

    @Test
    @DisplayName("Без префикса путь равен URI")
    void noPrefix_PathEqualsUri() {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/status");

        assertThat(RequestPaths.pathWithinApplication(raw)).isEqualTo("/api/status");
    }

    @Test
    @DisplayName("Контекстный путь сервлета отрезается так же, как префикс")
    void contextPath_IsStripped() {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/app/api/status");
        raw.setContextPath("/app");

        assertThat(RequestPaths.pathWithinApplication(raw)).isEqualTo("/api/status");
    }

    @Test
    @DisplayName("URI без ведущего контекстного пути возвращается как есть, а не обрезается вслепую")
    void uriNotStartingWithContextPath_ReturnedAsIs() {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/status");
        raw.setContextPath("/other");

        assertThat(RequestPaths.pathWithinApplication(raw)).isEqualTo("/api/status");
    }

    /** Пропустить запрос через настоящий {@link ForwardedHeaderFilter} и вернуть то, что увидит цепочка. */
    static HttpServletRequest throughForwardedHeaderFilter(MockHttpServletRequest raw) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(raw, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
