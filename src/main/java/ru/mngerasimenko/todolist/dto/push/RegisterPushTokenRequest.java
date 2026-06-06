package ru.mngerasimenko.todolist.dto.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;

/**
 * Запрос на регистрацию FCM push-токена устройства.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPushTokenRequest {

    @NotBlank(message = "FCM token is required")
    @JsonProperty("fcm_token")
    private String fcmToken;

    @NotBlank(message = "Device ID is required")
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
}
