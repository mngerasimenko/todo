package ru.mngerasimenko.todolist.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты EmailTrackingController — soft-gate HMAC-подписи.
 * Пиксель / редирект отдаются ВСЕГДА (не ломаем рендер картинки и уже разосланные письма),
 * а {@code log.info} (из него считаются метрики open/click) — только при валидной подписи.
 */
class EmailTrackingControllerTest {

    private CryptoService cryptoService;
    private EmailTrackingController controller;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        cryptoService = mock(CryptoService.class);
        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setBaseUrl("https://todo.keepware.ru");
        controller = new EmailTrackingController(emailProperties, cryptoService);

        logger = (Logger) LoggerFactory.getLogger(EmailTrackingController.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void trackOpen_ValidSignature_ReturnsPixelAndLogs() {
        when(cryptoService.verifySignature("open:42", "goodsig")).thenReturn(true);

        ResponseEntity<byte[]> response = controller.trackOpen(42L, "goodsig");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(loggedTracking()).isTrue();
    }

    @Test
    void trackOpen_InvalidSignature_ReturnsPixelButDoesNotLog() {
        when(cryptoService.verifySignature("open:42", "bad")).thenReturn(false);

        ResponseEntity<byte[]> response = controller.trackOpen(42L, "bad");

        // пиксель отдаётся всегда, но событие не логируется (нельзя накрутить метрику без ключа)
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void trackOpen_MissingSignature_ReturnsPixelButDoesNotLog() {
        when(cryptoService.verifySignature("open:42", null)).thenReturn(false);

        ResponseEntity<byte[]> response = controller.trackOpen(42L, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void trackClick_ValidSignature_RedirectsAndLogs() {
        when(cryptoService.verifySignature("click:7", "goodsig")).thenReturn(true);

        ResponseEntity<Void> response = controller.trackClick(7L, "goodsig");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://todo.keepware.ru/open");
        assertThat(loggedTracking()).isTrue();
    }

    @Test
    void trackClick_InvalidSignature_RedirectsButDoesNotLog() {
        when(cryptoService.verifySignature("click:7", "bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.trackClick(7L, "bad");

        // редирект выполняется всегда (не ломаем клик из уже разосланных писем), но не логируется
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://todo.keepware.ru/open");
        assertThat(appender.list).isEmpty();
    }

    private boolean loggedTracking() {
        return appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("email-tracking"));
    }
}
