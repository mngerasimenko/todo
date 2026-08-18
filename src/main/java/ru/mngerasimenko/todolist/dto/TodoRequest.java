package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.model.ReminderScope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * DTO входящего запроса на создание/обновление задачи.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoRequest {

    @JsonProperty("id")
    private Long id;

    @NotBlank(message = "Todo name is required")
    @Size(min = 1, max = 120, message = "Todo name must be between 1 and 120 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Name contains invalid characters")
    private String name;

    @JsonProperty("date_time")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dateTime;

    private Boolean done;

    @JsonProperty("user_id")
    @NotNull(message = "User ID is required")
    private Long userId;

    @JsonProperty("list_id")
    @NotNull(message = "List ID is required")
    private Long listId;

    @JsonProperty("is_private")
    private boolean isPrivate;

    @JsonProperty("due_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @JsonProperty("due_time")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime dueTime;

    @JsonProperty("due_timezone")
    private String dueTimezone;

    @JsonProperty("remind_before_minutes")
    private Integer remindBeforeMinutes;

    @JsonProperty("reminder_scope")
    private ReminderScope reminderScope;

    /**
     * true, если тело запроса несло хотя бы один due-ключ (due_date/due_time/due_timezone/
     * remind_before_minutes/reminder_scope) — не важно, с каким значением, включая null.
     * Отсутствие ключа в JSON не вызывает сеттер вообще, поэтому флаг остаётся false —
     * этим отличается "клиент про срок не знает" (веб-форма, текущий Android TodoRequest)
     * от "клиент явно снимает срок" (due_date: null). См. TodoServiceImpl.updateTodo.
     * Не часть JSON-контракта.
     */
    @JsonIgnore
    private boolean dueFieldsProvided;

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
        this.dueFieldsProvided = true;
    }

    public void setDueTime(LocalTime dueTime) {
        this.dueTime = dueTime;
        this.dueFieldsProvided = true;
    }

    public void setDueTimezone(String dueTimezone) {
        this.dueTimezone = dueTimezone;
        this.dueFieldsProvided = true;
    }

    public void setRemindBeforeMinutes(Integer remindBeforeMinutes) {
        this.remindBeforeMinutes = remindBeforeMinutes;
        this.dueFieldsProvided = true;
    }

    public void setReminderScope(ReminderScope reminderScope) {
        this.reminderScope = reminderScope;
        this.dueFieldsProvided = true;
    }
}
