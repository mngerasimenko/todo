package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Публичная информация о приглашении (без авторизации).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteInfoResponse {

    @JsonProperty("list_name")
    private String listName;

    @JsonProperty("inviter_name")
    private String inviterName;

    @JsonProperty("expires_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;
}
