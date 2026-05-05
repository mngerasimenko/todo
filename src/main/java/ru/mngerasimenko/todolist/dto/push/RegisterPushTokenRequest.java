package ru.mngerasimenko.todolist.dto.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
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
}
