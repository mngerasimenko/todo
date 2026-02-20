package ru.mngerasimenko.todolist.dto.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ с информацией об участнике аккаунта.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountMemberResponse {

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("user_name")
    private String userName;

    private String role;

    @JsonProperty("joined_at")
    private String joinedAt;
}
