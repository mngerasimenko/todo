package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.service.MessageService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Locale;

/**
 * Контроллер отписки от reminder-напоминаний по одноразовой email-ссылке.
 * Эндпоинт открытый (permitAll) — получатель email не авторизован.
 *
 * Phase 3.3 + unsubscribe (см. fromIdeas/response_phase33_unsubscribe_risk_2026-05-17.md).
 * Отписывает от обоих типов reminder'ов (3d onboarding + 7d inactive), forward-looking.
 *
 * Локализация: для успешной отписки берётся {@code preferredEmailLocale} пользователя
 * (юзер видит ту же локаль что и в email-письме). Для невалидного/уже использованного
 * токена — резолвится из заголовка {@code Accept-Language}, fallback на ru.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class EmailUnsubscribeController {

    private final UserService userService;
    private final MessageService messageService;

    /**
     * Отписка от reminder-напоминаний.
     * Возвращает локализованную HTML-страницу с подтверждением (или нейтральным
     * сообщением для истёкших/уже использованных токенов и concurrent-кейсов).
     */
    @GetMapping("/unsubscribe-reminder")
    public ResponseEntity<String> unsubscribeReminder(
            @RequestParam(name = "token", required = false) String token,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {

        try {
            String userLocale = userService.unsubscribeFromReminders(token);
            log.info("[unsubscribe-reminder] Успешная отписка, locale={}", userLocale);
            return htmlResponse(successHtml(toLocale(userLocale)));
        } catch (UserNotFoundException e) {
            // Невалидный, пустой или уже использованный токен — нейтральное сообщение
            // (не различаем кейсы, чтобы не давать enumeration-сигнал).
            log.info("[unsubscribe-reminder] Невалидный или уже использованный токен");
            return htmlResponse(alreadyUnsubscribedHtml(resolveLocaleFromHeader(acceptLanguage)));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Concurrent hit: другой поток уже отписал юзера — отдаём ту же страницу
            // «уже использовано», но логируем отдельно для наблюдаемости race-кейсов.
            log.info("[unsubscribe-reminder] Concurrent отписка, токен уже погашен другим потоком");
            return htmlResponse(alreadyUnsubscribedHtml(resolveLocaleFromHeader(acceptLanguage)));
        }
    }

    private ResponseEntity<String> htmlResponse(String body) {
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    /**
     * Грубый парсинг первого языка из заголовка {@code Accept-Language} (берём язык
     * с наивысшим приоритетом, отбрасываем q-значения). Поддерживаем только {@code ru}
     * и {@code en} — всё остальное → fallback на {@code ru} (текущая основная аудитория
     * 79% RU по Firebase Analytics).
     */
    private Locale resolveLocaleFromHeader(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return new Locale("ru");
        }
        String lang = acceptLanguage.split(",")[0].split(";")[0].trim().toLowerCase();
        if (lang.startsWith("en")) {
            return Locale.ENGLISH;
        }
        return new Locale("ru");
    }

    private Locale toLocale(String tag) {
        if (tag == null || tag.isBlank()) {
            return new Locale("ru");
        }
        return Locale.forLanguageTag(tag);
    }

    private String successHtml(Locale locale) {
        return pageHtml(
                locale,
                messageService.getMessage("unsubscribe.success.title", locale),
                messageService.getMessage("unsubscribe.success.body", locale),
                messageService.getMessage("unsubscribe.success.note", locale)
        );
    }

    private String alreadyUnsubscribedHtml(Locale locale) {
        String contactEmail = "todo-noreply@keepware.ru";
        return pageHtml(
                locale,
                messageService.getMessage("unsubscribe.invalid.title", locale),
                messageService.getMessage("unsubscribe.invalid.body", locale),
                messageService.getMessage("unsubscribe.invalid.contact", locale, contactEmail)
        );
    }

    private String pageHtml(Locale locale, String title, String body, String note) {
        String cta = messageService.getMessage("unsubscribe.cta.open", locale);
        String lang = locale.getLanguage().startsWith("en") ? "en" : "ru";
        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 500px; margin: 80px auto; padding: 24px; text-align: center; color: #333; }
                        h1 { color: #4285F4; }
                        a { color: #4285F4; text-decoration: none; }
                    </style>
                </head>
                <body>
                    <h1>%s</h1>
                    <p>%s</p>
                    <p>%s</p>
                    <p><a href="https://todo.keepware.ru/">%s</a></p>
                </body>
                </html>
                """.formatted(lang, title, title, body, note, cta);
    }
}
