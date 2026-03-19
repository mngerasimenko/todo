package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime joinedAt;
}
