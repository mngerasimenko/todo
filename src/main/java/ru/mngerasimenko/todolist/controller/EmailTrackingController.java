package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.util.Base64;

/**
 * Контроллер для трекинга email-напоминаний.
 * Эндпоинты открытые (permitAll) — получатель email не авторизован.
 *
 * /api/track/open/{userId} — tracking pixel (1x1 PNG), логирует открытие письма
 * /api/track/click/{userId} — redirect на /open, логирует клик по кнопке
 */
@Slf4j
@RestController
@RequestMapping("/api/track")
@RequiredArgsConstructor
public class EmailTrackingController {

    private final EmailProperties emailProperties;
    private final CryptoService cryptoService;

    /** 1x1 прозрачный PNG (89 байт) */
    private static final byte[] TRANSPARENT_PIXEL = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQAB" +
            "Nl7BcQAAAABJRU5ErkJggg=="
    );

    /**
     * Tracking pixel — email-клиент загружает картинку при открытии письма.
     */
    @GetMapping("/open/{userId}")
    public ResponseEntity<byte[]> trackOpen(@PathVariable Long userId,
                                            @RequestParam(name = "s", required = false) String signature) {
        // Soft-gate: пиксель отдаём всегда (не ломаем рендер картинки и уже разосланные письма),
        // но событие логируем только при валидной подписи — подделать метрику без ключа нельзя.
        if (cryptoService.verifySignature("open:" + userId, signature)) {
            log.info("[email-tracking] Письмо открыто: userId={}", userId);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().getHeaderValue())
                .body(TRANSPARENT_PIXEL);
    }

    /**
     * Клик по кнопке «Открыть приложение» — redirect на /open (deep link).
     */
    @GetMapping("/click/{userId}")
    public ResponseEntity<Void> trackClick(@PathVariable Long userId,
                                           @RequestParam(name = "s", required = false) String signature) {
        // Soft-gate: редирект выполняем всегда (не ломаем клик из уже разосланных писем),
        // логируем только при валидной подписи.
        if (cryptoService.verifySignature("click:" + userId, signature)) {
            log.info("[email-tracking] Клик по кнопке: userId={}", userId);
        }

        String redirectUrl = emailProperties.getBaseUrl() + "/open";
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }
}
