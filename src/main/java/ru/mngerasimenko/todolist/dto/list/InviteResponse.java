package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Ответ с ссылкой-приглашением в список.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteResponse {

    @JsonProperty("invite_link")
    private String inviteLink;

    @JsonProperty("expires_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;
}
