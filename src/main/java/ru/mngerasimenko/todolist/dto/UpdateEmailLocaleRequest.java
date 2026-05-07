package ru.mngerasimenko.todolist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для смены языка email-уведомлений пользователя.
 * Используется в эндпоинте {@code PATCH /api/users/me/email-locale}.
 *
 * <p>Не валидируем формат строго (BCP-47 широкий). Если прислана неподдерживаемая
 * локаль, MessageSource сделает fallback на defaultLocale ({@code "ru"}) при отправке писем.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmailLocaleRequest {

    @NotBlank(message = "Locale is required")
    @Size(max = 8, message = "Locale must not exceed 8 characters")
    private String locale;
}
