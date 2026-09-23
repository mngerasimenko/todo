package ru.mngerasimenko.todolist.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Версия клиента в строке лога. Без неё ошибку из прод-логов нельзя привязать к релизу:
 * до 1.2.8 отличить версии удавалось лишь косвенно, по наличию client_request_id.
 * <p>
 * Значение приходит из заголовка, то есть полностью под контролем клиента, и на нём
 * ничего не завязано — только логирование. Отсюда проверки на инъекцию в лог.
 */
class LoggingFilterTest {

    private LoggingFilter filter;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        filter = new LoggingFilter();
        logger = (Logger) LoggerFactory.getLogger(LoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String logLineFor(String platform, String version) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/lists");
        if (platform != null) {
            request.addHeader("X-App-Platform", platform);
        }
        if (version != null) {
            request.addHeader("X-App-Version", version);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("Запрос без заголовков клиента: app: - вместо пустоты")
    void noHeaders_logsDash() throws Exception {
        assertThat(logLineFor(null, null)).contains("app: -");
    }

    @Test
    @DisplayName("Android с версией: app: android/29")
    void androidWithVersion_logsBoth() throws Exception {
        assertThat(logLineFor("android", "29")).contains("app: android/29");
    }

    @Test
    @DisplayName("Платформа без версии: вторая половина — прочерк, а не пусто")
    void platformWithoutVersion_logsDashForVersion() throws Exception {
        assertThat(logLineFor("android", null)).contains("app: android/-");
    }

    @Test
    @DisplayName("Перевод строки в заголовке не создаёт вторую строку лога (log injection)")
    void newlineInHeader_isStripped() throws Exception {
        String line = logLineFor("android", "29\nINFO подделанная строка лога");

        assertThat(line).doesNotContain("\n");
        assertThat(line).doesNotContain("подделанная");
        assertThat(line).contains("app: android/29");
    }

    @Test
    @DisplayName("Управляющие символы и кавычки выбрасываются, а не экранируются")
    void controlCharsAndQuotes_areDropped() throws Exception {
        String line = logLineFor("and\u0007roid\"", "2\t9");

        assertThat(line).contains("app: android/29");
        assertThat(line).doesNotContain("\u0007");
        assertThat(line).doesNotContain("\"");
    }

    @Test
    @DisplayName("Слишком длинное значение обрезается: клиент не диктует длину строки лога")
    void tooLongValue_isTruncated() throws Exception {
        String line = logLineFor("android", "9".repeat(100));

        assertThat(line).contains("app: android/" + "9".repeat(16));
        assertThat(line).doesNotContain("9".repeat(17));
    }

    @Test
    @DisplayName("Заголовок из одних недопустимых символов равен отсутствию заголовка")
    void allCharsDropped_behavesAsMissing() throws Exception {
        assertThat(logLineFor("!!!", "@@@")).contains("app: -");
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
