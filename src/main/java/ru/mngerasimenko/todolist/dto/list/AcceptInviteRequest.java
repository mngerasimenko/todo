package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса на принятие приглашения в список по токену.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcceptInviteRequest {

    @NotBlank(message = "Токен приглашения обязателен")
    private String token;
}
