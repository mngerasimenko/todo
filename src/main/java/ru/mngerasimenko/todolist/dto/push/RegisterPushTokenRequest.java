package ru.mngerasimenko.todolist.dto.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Size(max = 8, message = "Locale must not exceed 8 characters")
    private String locale;
}
