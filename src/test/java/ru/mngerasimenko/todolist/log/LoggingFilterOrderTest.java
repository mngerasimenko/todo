package ru.mngerasimenko.todolist.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Фильтр обязан стоять снаружи цепочки Spring Security — иначе весь отбитый трафик
 * (401 на битом токене, 429 от rate-limit) в лог не попадает, а именно его и интереснее
 * всего привязывать к версии клиента.
 * <p>
 * Проверку аннотации рефлексией это не заменяет, а вытесняет: та читала {@code @Order} с того
 * же класса, где он объявлен, и оставалась зелёной, даже если бы Spring Boot его не применял
 * или рядом появился {@code FilterRegistrationBean} со своим порядком. Здесь запрос проходит
 * через реальную цепочку поднятого контекста.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoggingFilterOrderTest {

    @Autowired
    private MockMvc mockMvc;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
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
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("Запрос, отбитый security с 401, всё равно оставляет строку лога")
    void rejectedRequest_isStillLogged() throws Exception {
        mockMvc.perform(get("/api/lists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer явно-битый-токен")
                        .header("X-App-Platform", "android")
                        .header("X-App-Version", "29"))
                .andExpect(status().isUnauthorized());

        assertThat(appender.list)
                .as("фильтр внутри security-цепочки не увидел бы этот запрос вовсе")
                .isNotEmpty();
        assertThat(appender.list.get(appender.list.size() - 1).getFormattedMessage())
                .contains("request URI: /api/lists")
                .contains("response status: 401")
                .contains("app: android/29");
    }
}
