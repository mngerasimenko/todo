package ru.mngerasimenko.todolist.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ на ручной триггер напоминания о неактивности.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InactiveReminderTriggerResponse {

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("push_sent")
    private boolean pushSent;

    @JsonProperty("email_sent")
    private boolean emailSent;
}
