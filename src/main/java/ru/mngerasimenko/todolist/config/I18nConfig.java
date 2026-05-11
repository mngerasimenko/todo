package ru.mngerasimenko.todolist.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Конфигурация локализации для email-шаблонов и FCM push.
 * <p>
 * REST API намеренно не локализуется ({@code GlobalExceptionHandler} и Bean Validation messages
 * всегда на английском) — клиенты переводят сами по {@code error}/{@code status}.
 * Локализация применяется только к серверной отправке писем и push-уведомлений.
 * <p>
 * Здесь нет {@code LocaleResolver} — локаль для писем и push передаётся явно
 * через {@link ru.mngerasimenko.todolist.service.MessageService} (поскольку
 * @{@code Async}-отправка происходит вне HTTP-контекста).
 */
@Configuration
public class I18nConfig {

    /**
     * MessageSource для email и push.
     * Бандлы: {@code messages.properties} (fallback ru), {@code messages_ru.properties}, {@code messages_en.properties}.
     */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setDefaultLocale(Locale.forLanguageTag("ru"));
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
