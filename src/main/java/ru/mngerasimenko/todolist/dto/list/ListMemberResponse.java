package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ с информацией об участнике списка задач.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListMemberResponse {

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("user_name")
    private String userName;

    private String role;

    @JsonProperty("joined_at")
    private String joinedAt;
}
