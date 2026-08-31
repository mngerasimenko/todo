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
import ru.mngerasimenko.todolist.util.AcceptLanguageParser;

import java.util.Locale;
import java.util.Set;

/**
 * Контроллер отписки от reminder-напоминаний по одноразовой email-ссылке.
 * Эндпоинт открытый (permitAll) — получатель email не авторизован.
 *
 * Phase 3.3 + unsubscribe (см. fromIdeas/response_phase33_unsubscribe_risk_2026-05-17.md).
 * Отписывает от обоих типов reminder'ов (3d onboarding + 7d inactive), forward-looking.
 *
 * С Task 7 один и тот же эндпоинт обслуживает второе, отдельное согласие: параметр
 * {@code scope=todo_due} отписывает от напоминаний о сроках собственных задач
 * ({@code todoReminderEmailEnabled}), не трогая {@code reminderOptOut}. Без параметра —
 * прежнее поведение. Один токен отключает ровно одно согласие.
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

    /** Значение {@code scope}, отключающее todo-due согласие вместо reminderOptOut. */
    private static final String SCOPE_TODO_DUE = "todo_due";

    /** Языки, на которых существуют HTML-страницы отписки (ключи в {@code messages*.properties}). */
    private static final String EN = "en";
    private static final String DEFAULT_PAGE_LANGUAGE = "ru";
    private static final Set<String> SUPPORTED_PAGE_LANGUAGES = Set.of(DEFAULT_PAGE_LANGUAGE, EN);
    private static final Locale EN_LOCALE = Locale.ENGLISH;
    private static final Locale DEFAULT_PAGE_LOCALE = new Locale(DEFAULT_PAGE_LANGUAGE);

    /**
     * Отписка от reminder-напоминаний.
     * Возвращает локализованную HTML-страницу с подтверждением (или нейтральным
     * сообщением для истёкших/уже использованных токенов и concurrent-кейсов).
     * <p>
     * {@code scope=todo_due} → отключает напоминания о сроках задач; отсутствие параметра
     * или любое другое значение → прежнее поведение (маркетинговые reminder-напоминания).
     */
    @GetMapping("/unsubscribe-reminder")
    public ResponseEntity<String> unsubscribeReminder(
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {

        try {
            String userLocale = SCOPE_TODO_DUE.equals(scope)
                    ? userService.unsubscribeFromTodoReminders(token)
                    : userService.unsubscribeFromReminders(token);
            log.info("[unsubscribe-reminder] Успешная отписка, locale={}, scope={}", userLocale, scope);
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
     * Язык страницы из заголовка {@code Accept-Language}: берём самый приемлемый для клиента
     * из поддерживаемых, всё остальное → fallback на {@code ru} (текущая основная аудитория
     * 79% RU по Firebase Analytics).
     * <p>
     * Разбор — общий с {@code AuthController}, в {@link AcceptLanguageParser}: заголовок на этом
     * открытом эндпоинте приходит произвольный, а прежний разбор по первому элементу списка
     * игнорировал q-веса и отдавал русскую страницу клиенту, прямо попросившему английскую.
     * <p>
     * Package-private для unit-тестирования.
     */
    Locale resolveLocaleFromHeader(String acceptLanguage) {
        return supportedLocale(AcceptLanguageParser.bestSupportedLanguage(
                acceptLanguage, SUPPORTED_PAGE_LANGUAGES, DEFAULT_PAGE_LANGUAGE));
    }

    /**
     * Локаль страницы по значению {@code preferred_email_locale} из БД. Значение сводится
     * к тому же набору {@link #SUPPORTED_PAGE_LANGUAGES}, что и язык из заголовка: колонка
     * принимает любой BCP-47 тег ({@code "eng"}, {@code "de"}), а страниц у нас две.
     * Без этого сведения {@code "eng"} попадал в {@code Locale.forLanguageTag} как есть,
     * бандла {@code messages_eng} не находилось, текст приходил русский — но атрибут
     * {@code lang} объявлял страницу английской.
     */
    private Locale toLocale(String tag) {
        if (tag == null || tag.isBlank()) {
            return supportedLocale(null);
        }
        // Регистр в колонке произвольный: явный locale клиента сохраняется как прислан,
        // поэтому там встречается и "EN".
        return supportedLocale(AcceptLanguageParser.primarySubtagOf(tag.toLowerCase(Locale.ROOT)));
    }

    /** Единственное место, где язык превращается в локаль страницы. */
    private Locale supportedLocale(String language) {
        return EN.equals(language) ? EN_LOCALE : DEFAULT_PAGE_LOCALE;
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
        // Локаль сюда приходит только из supportedLocale, поэтому язык берётся из неё напрямую:
        // прежний startsWith("en") считал английскими и "eng"/"enm", у которых нет ни бандла,
        // ни отношения к английскому.
        String lang = EN_LOCALE.equals(locale) ? EN : DEFAULT_PAGE_LANGUAGE;
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
