package ru.mngerasimenko.todolist.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Конфигурация локализации: email-шаблоны, FCM push и сообщения bean validation.
 * <p>
 * Машиночитаемые поля ответа ({@code error}, {@code status}) всегда английские. За
 * {@code Accept-Language} следуют сообщения bean validation (ответ 400 {@code Validation Failed}) —
 * см. {@link AcceptLanguageMessageInterpolator} — и маска неудачного входа (401): её
 * {@code AuthController} берёт по ключу {@code auth.invalid-credentials}, потому что Android
 * показывает {@code message} сервера пользователю как есть.
 * <p>
 * Остальные тексты {@code message} {@code GlobalExceptionHandler} берёт из исключений как есть, и
 * единой картины там нет: сервисы бросают кто русский текст, кто английский, а тексты Spring Security
 * (401 при неверном пароле) и вовсе переводятся сами — по {@code LocaleContextHolder}, который
 * следует за тем же заголовком. Именно поэтому {@code GlobalExceptionHandler} больше не узнаёт
 * неудачный вход по английской строке {@code "Bad credentials"}: на запросе с
 * {@code Accept-Language: ru} до подмены он не доходил. Маскировка переехала в
 * {@code AuthController}, опирается на тип исключения, а текст берёт из бандла. Единый язык
 * остальных текстов — по-прежнему отдельная задача.
 * <p>
 * Здесь нет {@code LocaleResolver} — локаль для писем и push передаётся явно
 * через {@link ru.mngerasimenko.todolist.service.MessageService} (поскольку
 * @{@code Async}-отправка происходит вне HTTP-контекста).
 */
@Configuration
public class I18nConfig {

    /**
     * MessageSource для email, push и сообщений bean validation.
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

    /**
     * Валидатор вместо автоконфигурируемого: DTO ссылаются на тексты ключами ({@code {validation.password.size}}),
     * ключи разрешаются через {@link #messageSource()}, язык выбирает {@link AcceptLanguageMessageInterpolator}.
     * Бин заменяет {@code ValidationAutoConfiguration#defaultValidator} (тот объявлен
     * {@code @ConditionalOnMissingBean(Validator.class)}) и повторяет его во всём, кроме правила выбора языка:
     * ключи разрешает тот же {@link MessageInterpolatorFactory}, а {@code ValidationConfigurationCustomizer}
     * применяются так же — иначе добавленный позже customizer молча перестал бы работать.
     * <p>
     * {@code static} — чтобы бин не тянул создание конфигурации раньше {@code BeanPostProcessor}'ов
     * (валидатор нужен {@code MethodValidationPostProcessor}). Полностью это не спасает — параметр
     * {@code messageSource} всё равно создаётся из этой же конфигурации, — но BPP берёт валидатор
     * лениво, через {@code ObjectProvider}, поэтому до полной инициализации контекста дело не доходит.
     */
    @Bean
    public static LocalValidatorFactoryBean defaultValidator(
            MessageSource messageSource,
            ObjectProvider<ValidationConfigurationCustomizer> customizers) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setConfigurationInitializer(configuration ->
                customizers.orderedStream().forEach(customizer -> customizer.customize(configuration)));
        validator.setMessageInterpolator(new AcceptLanguageMessageInterpolator(
                new MessageInterpolatorFactory(messageSource).getObject()));
        return validator;
    }
}
