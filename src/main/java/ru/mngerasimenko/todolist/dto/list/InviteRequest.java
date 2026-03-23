package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса на создание приглашения в список.
 * Если email указан — отправляется письмо с приглашением.
 * Если null — возвращается только ссылка для копирования.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteRequest {

    @Email(message = "Некорректный формат email")
    private String email;
}
