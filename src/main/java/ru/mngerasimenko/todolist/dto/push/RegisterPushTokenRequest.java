package ru.mngerasimenko.todolist.dto.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;
import ru.mngerasimenko.todolist.util.LocaleNormalizer;

/**
 * Запрос на регистрацию FCM push-токена устройства.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPushTokenRequest {

    @NotBlank(message = "{validation.push.fcm-token.required}")
    @JsonProperty("fcm_token")
    private String fcmToken;

    @NotBlank(message = "{validation.push.device-id.required}")
    @JsonProperty("device_id")
    private String deviceId;

    /**
     * Язык push-уведомлений на этом устройстве в формате BCP-47 (e.g. "ru", "en").
     * Опциональное поле для обратной совместимости со старыми Android-клиентами:
     * если не указано — сервер использует "ru" (см. PushNotificationServiceImpl.registerToken).
     */
    @JsonProperty("locale")
    @Size(max = LocaleValidation.MAX_LENGTH, message = LocaleValidation.MAX_LENGTH_MESSAGE)
    @Pattern(regexp = LocaleValidation.PATTERN_OPTIONAL, message = LocaleValidation.PATTERN_MESSAGE)
    private String locale;

    /**
     * Нормализует тег при десериализации до {@code language[-REGION]} —
     * см. {@link LocaleNormalizer}. Клиент перерегистрирует токен при каждой смене
     * языка в Settings и шлёт сырой {@code Locale.getDefault().toLanguageTag()},
     * поэтому сюда приходит тот же раздутый тег, что и в регистрацию.
     */
    public void setLocale(String locale) {
        this.locale = LocaleNormalizer.normalize(locale);
    }
}
