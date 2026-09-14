package ru.mngerasimenko.todolist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;
import ru.mngerasimenko.todolist.util.LocaleNormalizer;

/**
 * DTO для смены языка email-уведомлений пользователя.
 * Используется в эндпоинте {@code PATCH /api/users/me/email-locale}.
 *
 * <p>Не валидируем формат строго (BCP-47 широкий). Если прислана неподдерживаемая
 * локаль, MessageSource сделает fallback на defaultLocale ({@code "ru"}) при отправке писем.
 *
 * <p>Намеренно без {@code @Builder} и {@code @AllArgsConstructor}: у DTO с единственным
 * полем Jackson принимает сгенерированный одноаргументный конструктор за <i>delegating
 * creator</i> и на теле в виде голой JSON-строки пишет поле напрямую, минуя
 * {@link #setLocale(String)} — то есть мимо нормализации. Проверено тестом
 * {@code LocaleNormalizationBindingTest.bareJsonString_DoesNotBypassNormalization}.
 */
@Data
@NoArgsConstructor
public class UpdateEmailLocaleRequest {

    @NotBlank(message = "Locale is required")
    @Size(max = LocaleValidation.MAX_LENGTH, message = LocaleValidation.MAX_LENGTH_MESSAGE)
    @Pattern(regexp = LocaleValidation.PATTERN, message = LocaleValidation.PATTERN_MESSAGE)
    private String locale;

    /**
     * Нормализует тег при десериализации до {@code language[-REGION]} —
     * см. {@link LocaleNormalizer}. Валидация ниже применяется уже к результату.
     */
    public void setLocale(String locale) {
        this.locale = LocaleNormalizer.normalize(locale);
    }
}
