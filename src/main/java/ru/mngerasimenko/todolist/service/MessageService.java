package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Обёртка над {@link MessageSource} для локализованных строк email и FCM push.
 * <p>
 * Локаль передаётся явно — отправка писем/пушей идёт через {@code @Async}
 * вне HTTP-контекста, поэтому полагаться на {@code LocaleContextHolder} нельзя.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageSource messageSource;

    /**
     * Возвращает локализованную строку по ключу.
     * Если ключ не найден — возвращает сам ключ (для упрощения отладки в шаблонах).
     */
    public String getMessage(String key, Locale locale) {
        return getMessage(key, locale, (Object[]) null);
    }

    /**
     * Возвращает локализованную строку по ключу с параметрами для подстановки {@code {0}}, {@code {1}}, ...
     */
    public String getMessage(String key, Locale locale, Object... args) {
        try {
            return messageSource.getMessage(key, args, locale);
        } catch (NoSuchMessageException e) {
            return key;
        }
    }
}
