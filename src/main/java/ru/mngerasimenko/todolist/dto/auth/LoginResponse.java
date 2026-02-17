package ru.mngerasimenko.todolist.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.UserResponse;

/**
 * DTO для ответа на успешную аутентификацию
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /**
     * Access токен для доступа к защищённым эндпоинтам
     */
    @JsonProperty("access_token")
    private String accessToken;

    /**
     * Refresh токен для обновления access токена
     */
    @JsonProperty("refresh_token")
    private String refreshToken;

    /**
     * Время жизни access токена в секундах
     */
    @JsonProperty("expires_in")
    private Long expiresIn;

    /**
     * Тип токена (всегда "Bearer")
     */
    @JsonProperty("token_type")
    @Builder.Default
    private String tokenType = "Bearer";

    /**
     * Информация о пользователе
     */
    private UserResponse user;
}
