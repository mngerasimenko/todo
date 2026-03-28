package ru.mngerasimenko.todolist.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для запроса выхода из системы.
 * Refresh-токен опционален — если передан, будет отозван.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {

    @JsonProperty("refresh_token")
    private String refreshToken;
}
