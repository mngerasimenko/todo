package ru.mngerasimenko.todolist.dto.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ с информацией об аккаунте.
 * Поле role — роль текущего пользователя в данном аккаунте (ADMIN/USER).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {

    private Long id;

    private String name;

    /**
     * Роль текущего пользователя в аккаунте.
     */
    private String role;

    @JsonProperty("created_at")
    private String createdAt;
}
