package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;
import ru.mngerasimenko.todolist.util.LocaleNormalizer;

/**
 * DTO для запроса регистрации пользователя
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    /**
     * Email пользователя
     */
    @NotBlank(message = "{validation.email.required}")
    @Email(message = "{validation.email.format}")
    @Size(max = EmailValidation.MAX_LENGTH, message = EmailValidation.MAX_LENGTH_MESSAGE)
    private String email;

    public void setEmail(String email) {
        this.email = email != null ? email.trim().toLowerCase() : null;
    }

    /**
     * Имя пользователя
     */
    @NotBlank(message = "{validation.name.required}")
    @Size(min = 2, max = 128, message = "{validation.name.size}")
    @Pattern(regexp = "^[^<>]*$", message = "{validation.name.invalid-characters}")
    private String name;

    /**
     * Пароль
     */
    @NotBlank(message = "{validation.password.required}")
    @Size(min = 5, max = 128, message = "{validation.password.size}")
    private String password;

    /**
     * Язык писем для нового пользователя в формате BCP-47 (e.g. "ru", "en").
     * Опциональное поле — если не указано, сервер использует Accept-Language
     * заголовок запроса, fallback "ru" (см. UserServiceImpl.createUser).
     * <p>
     * Валидация применяется к уже нормализованному значению — см. {@link #setLocale(String)}.
     */
    @Size(max = LocaleValidation.MAX_LENGTH, message = LocaleValidation.MAX_LENGTH_MESSAGE)
    @Pattern(regexp = LocaleValidation.PATTERN_OPTIONAL, message = LocaleValidation.PATTERN_MESSAGE)
    private String locale;

    /**
     * Нормализует тег при десериализации: клиент шлёт
     * {@code Locale.getDefault().toLanguageTag()} целиком, и на Android 13+ это
     * {@code ru-RU-u-fw-mon-ms-metric-mu-celsius}. Без сведения к {@code ru-RU}
     * такой запрос отклонялся @Size ещё до контроллера — регистрация падала
     * с HTTP 400 у всех, кто настроил региональные параметры вручную.
     */
    public void setLocale(String locale) {
        this.locale = LocaleNormalizer.normalize(locale);
    }
}
