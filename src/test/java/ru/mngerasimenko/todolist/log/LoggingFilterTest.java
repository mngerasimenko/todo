package ru.mngerasimenko.todolist.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Версия клиента в строке лога. Без неё ошибку из прод-логов нельзя привязать к релизу:
 * до 1.2.8 отличить версии удавалось лишь косвенно, по наличию client_request_id.
 * <p>
 * Всё, что идёт в строку от клиента — заголовки и URI, — недоверенное: строку лога читают
 * глазами и разбирают скриптами, и ни длину её, ни разделители в ней клиент задавать не должен.
 */
class LoggingFilterTest {

    private LoggingFilter filter;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        filter = new LoggingFilter();
        logger = (Logger) LoggerFactory.getLogger(LoggingFilter.class);
        previousLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        // Уровень возвращаем: иначе тест остаётся связанным со всеми следующими в этом форке JVM.
        logger.setLevel(previousLevel);
    }

    private MockHttpServletRequest request(String uri, String platform, String version) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (platform != null) {
            request.addHeader("X-App-Platform", platform);
        }
        if (version != null) {
            request.addHeader("X-App-Version", version);
        }
        return request;
    }

    private String logLine(MockHttpServletRequest request, int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    private String logLineFor(String platform, String version) throws Exception {
        return logLine(request("/api/lists", platform, version), 200);
    }

    @Test
    @DisplayName("Цепочка вызывается: фильтр, забывший её позвать, положил бы всё приложение")
    void chain_isAlwaysCalled() throws Exception {
        MockHttpServletRequest request = request("/api/lists", "android", "29");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Исключение из цепочки не съедает строку лога")
    void chainThrows_lineIsStillLogged() throws Exception {
        MockHttpServletRequest request = request("/api/lists", "android", "29");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("обрыв")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("app: android/29");
    }

    @Test
    @DisplayName("Запрос без заголовков клиента: обе половины — прочерки")
    void noHeaders_logsDashes() throws Exception {
        assertThat(logLineFor(null, null)).contains("app: -/-");
    }

    @Test
    @DisplayName("Android с версией: app: android/29")
    void androidWithVersion_logsBoth() throws Exception {
        assertThat(logLineFor("android", "29")).contains("app: android/29");
    }

    @Test
    @DisplayName("Платформа без версии и версия без платформы — прочерк вместо недостающей половины")
    void oneHalfMissing_logsDashForIt() throws Exception {
        assertThat(logLineFor("android", null)).contains("app: android/-");
        appender.list.clear();
        assertThat(logLineFor(null, "29")).contains("app: -/29");
    }

    @Test
    @DisplayName("Испорченное значение помечается, а не склеивается в правдоподобное")
    void mangledValue_isMarked() throws Exception {
        // Прокси по RFC 9110 склеивает два одноимённых заголовка: «29, 30» → выбрасыванием
        // символов получилось бы «2930» — несуществующая версия, которую скрипт посчитает.
        String line = logLineFor("android", "29, 30");

        assertThat(line).contains("app: android/?");
        assertThat(line).doesNotContain("2930");
    }

    @Test
    @DisplayName("Мусор отличается от отсутствия заголовка")
    void garbage_isNotTheSameAsMissing() throws Exception {
        String line = logLineFor("!!!", "@@@");

        assertThat(line).contains("app: ?/?");
        assertThat(line).doesNotContain("app: -/-");
    }

    @Test
    @DisplayName("Подделка разделителей формата не дорисовывает полей")
    void separatorSpoofing_doesNotForgeFields() throws Exception {
        // Значение короче MAX_VALUE_LENGTH намеренно: длинное отсекается потолком длины, и
        // тогда тест проходит, даже если посимвольный фильтр снять совсем.
        String line = logLineFor("a,status: 9", "29");

        assertThat(line).containsOnlyOnce("response status:");
        assertThat(line).contains("app: ?/29");
        assertThat(line).doesNotContain("status: 9,");
    }

    @Test
    @DisplayName("Перевод строки в заголовке не создаёт вторую строку лога (log injection)")
    void newlineInHeader_isStripped() throws Exception {
        // Короткий payload по той же причине, что и в тесте выше: иначе срабатывает потолок длины.
        String line = logLineFor("android", "29\nINFO x");

        assertThat(line).doesNotContain("\n");
        assertThat(line).contains("app: android/?");
    }

    @Test
    @DisplayName("Не-ASCII в заголовке не доходит до лога и не роняет фильтр")
    void nonAscii_isRejected() throws Exception {
        String line = logLineFor("андроид", "2🎉30");

        assertThat(line).contains("app: ?/?");
        assertThat(line).doesNotContain("андроид");
        assertThat(line).doesNotContain("🎉");
    }

    @Test
    @DisplayName("Пустое значение заголовка равно его отсутствию")
    void emptyHeaderValue_behavesAsMissing() throws Exception {
        assertThat(logLineFor("", "")).contains("app: -/-");
    }

    @Test
    @DisplayName("Слишком длинное значение помечается: клиент не диктует длину строки лога")
    void tooLongValue_isMarked() throws Exception {
        String line = logLineFor("android", "9".repeat(100));

        assertThat(line).contains("app: android/?");
        assertThat(line).doesNotContain("9".repeat(17));
    }

    @Test
    @DisplayName("Метод запроса тоже недоверенный: длинный глагол помечается")
    void longMethod_isMarked() throws Exception {
        // Пока фильтр стоял внутри security-цепочки, нестандартный метод отбивал StrictHttpFirewall
        // и до лога не доходил. @Order вынес фильтр наружу — и метод стал таким же клиентским
        // полем, как заголовки: 8 КБ в request line (предел Tomcat) раздували бы каждую строку.
        MockHttpServletRequest request = new MockHttpServletRequest("A".repeat(7000), "/api/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(400);
        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line.length()).isLessThan(600);
        assertThat(line).contains("request method: ?");
    }

    @Test
    @DisplayName("Обычные методы не трогаются")
    void standardMethods_areKept() throws Exception {
        assertThat(logLine(new MockHttpServletRequest("DELETE", "/api/lists/1"), 204))
                .contains("request method: DELETE");
    }

    @Test
    @DisplayName("Исключение из цепочки не выдаётся за успешный ответ")
    void chainThrows_statusIsNotReportedAsOk() throws Exception {
        // response.getStatus() в этот момент ещё 200: настоящий 500 проставит контейнер позже,
        // уже после раскрутки фильтров. Строка «200» на упавшем запросе врёт и глазу, и скрипту,
        // и попадает в подсчёт среднего времени ответа.
        MockHttpServletRequest request = request("/api/lists", "android", "29");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("обрыв")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("response status: 500");
    }

    @Test
    @DisplayName("Дефис как значение заголовка не притворяется его отсутствием")
    void dashValue_isMarked() throws Exception {
        // Дефис входит в разрешённые символы, поэтому клиент мог прислать ровно «-» и стать
        // неотличимым от того, кто заголовок не шлёт вовсе, — то есть тихо прикинуться старой версией.
        assertThat(logLineFor("-", "29")).contains("app: ?/29");
    }

    @Test
    @DisplayName("Длинный URI обрезается: иначе один запрос выедает ротацию логов")
    void longUri_isTruncated() throws Exception {
        // /api/swagger-ui/** пускается без авторизации и без rate-limit, а Tomcat принимает
        // строку запроса до 8 КБ: без потолка сотня таких запросов в секунду прокручивает
        // всю тридцатидневную историю логов за минуты.
        String line = logLine(request("/api/swagger-ui/" + "A".repeat(7000), "android", "29"), 404);

        assertThat(line.length()).isLessThan(600);
        assertThat(line).contains("/api/swagger-ui/");
        assertThat(line).contains("…");
        assertThat(line).contains("app: android/29");
    }

    @Test
    @DisplayName("Короткий URI не трогается")
    void shortUri_isKept() throws Exception {
        assertThat(logLineFor("android", "29")).contains("request URI: /api/lists,");
    }

    @Test
    @DisplayName("Не только 200: статус пишется тот, что в ответе")
    void nonOkStatus_isLogged() throws Exception {
        assertThat(logLine(request("/api/lists", "android", "29"), 401))
                .contains("response status: 401");
    }

    @Test
    @DisplayName("Фильтр объявлен снаружи security-цепочки, иначе 401 и 429 в лог не попадают")
    void filter_isOrderedOutsideSecurityChain() {
        // Замер на стейдже 23.09: без этого запрос с битым JWT отдаёт 401 и не оставляет
        // ни одной строки лога — цепочка Spring Security (порядок −100) отбивает его раньше.
        org.springframework.core.annotation.Order order =
                LoggingFilter.class.getAnnotation(org.springframework.core.annotation.Order.class);

        assertThat(order).as("у фильтра должен быть @Order").isNotNull();
        assertThat(order.value())
                .as("порядок обязан быть меньше −100 — приоритета цепочки Spring Security")
                .isLessThan(-100);
    }

    @Test
    @DisplayName("Прежняя часть строки цела: метод, URI, статус и время на месте")
    void existingFields_areKept() throws Exception {
        String line = logLineFor("android", "29");

        assertThat(line).contains("request method: GET");
        assertThat(line).contains("request URI: /api/lists");
        assertThat(line).contains("response status: 200");
        assertThat(line).contains("request processing time:");
    }
}
