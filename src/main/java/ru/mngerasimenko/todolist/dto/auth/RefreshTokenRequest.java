package ru.mngerasimenko.todolist.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для запроса обновления access токена.
 * Refresh-токен здесь опционален: веб-клиент передаёт его в HttpOnly-cookie,
 * а не в теле (#259), поэтому валидации @NotBlank/@Valid на этом поле нет —
 * «токен не предоставлен нигде» контроллер отдаёт как 401.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {

    /**
     * Refresh токен для получения нового access токена (источник — тело для Android).
     */
    @JsonProperty("refresh_token")
    private String refreshToken;
}
