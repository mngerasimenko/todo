package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.Todo;

import java.time.LocalTime;

/**
 * Маппер для конвертации между Todo, TodoDto, TodoRequest и TodoResponse.
 */
@Component
public class TodoMapper {

    /**
     * Конвертация сущности в полный DTO
     */
    public TodoDto toDto(Todo todo) {
        if (todo == null) {
            return null;
        }

        return TodoDto.builder()
                .id(todo.getId())
                .name(todo.getName())
                .createdAt(todo.getCreatedAt())
                .completedAt(todo.getCompletedAt())
                .done(todo.isDone())
                .isPrivate(todo.getIsPrivate())
                .userId(todo.getUser() != null ? todo.getUser().getId() : null)
                .userName(todo.getUser() != null ? todo.getUser().getName() : null)
                .userEmail(todo.getUser() != null ? todo.getUser().getEmail() : null)
                .creatorColor(todo.getUser() != null ? todo.getUser().getCreatedTaskColor() : null)
                .completorUserId(todo.getCompletorUser() != null ? todo.getCompletorUser().getId() : null)
                .completorUserName(todo.getCompletorUser() != null ? todo.getCompletorUser().getName() : null)
                .completorColor(todo.getCompletorUser() != null ? todo.getCompletorUser().getCompletedTaskColor() : null)
                .listId(todo.getTaskList() != null ? todo.getTaskList().getId() : null)
                .position(todo.getPosition())
                .dueDate(todo.getDueDate())
                .dueTime(todo.getDueTime())
                .dueTimezone(todo.getDueTimezone())
                .remindBeforeMinutes(todo.getRemindBeforeMinutes())
                .reminderScope(todo.getReminderScope())
                .build();
    }

    /**
     * Конвертация TodoDto в сущность (без установки связанных объектов)
     */
    public Todo toEntity(TodoDto todoDto) {
        if (todoDto == null) {
            return null;
        }

        Todo todo = new Todo();
        todo.setId(todoDto.getId());
        todo.setName(todoDto.getName());
        todo.setCreatedAt(todoDto.getCreatedAt());
        todo.setCompletedAt(todoDto.getCompletedAt());
        todo.setDone(todoDto.isDone());
        todo.setIsPrivate(todoDto.isPrivate());
        // Ключ идемпотентности переносится ТОЛЬКО сюда (создание). В toDto(Todo) его сознательно
        // нет: наружу он не отдаётся, клиент и так его знает. В updateEntityFromDto его тоже быть
        // не должно — иначе PUT затирал бы ключ и следующий ретрай создания не был бы распознан.
        todo.setClientRequestId(todoDto.getClientRequestId());
        todo.setUserId(todoDto.getUserId());
        // Нулевые значения от старых клиентов схлопываются в дефолты прямо здесь —
        // так due_time никогда не окажется null при NOT NULL в схеме.
        todo.setDueDate(todoDto.getDueDate());
        todo.setDueTime(todoDto.getDueTime() != null ? todoDto.getDueTime() : LocalTime.of(9, 0));
        todo.setDueTimezone(todoDto.getDueTimezone());
        todo.setRemindBeforeMinutes(todoDto.getRemindBeforeMinutes() != null ? todoDto.getRemindBeforeMinutes() : 0);
        todo.setReminderScope(todoDto.getReminderScope() != null ? todoDto.getReminderScope() : ReminderScope.SELF);
        return todo;
    }

    /**
     * Обновление существующей сущности из запроса (имя и флаг done)
     */
    public void updateEntityFromDto(TodoDto todoDto, Todo todo) {
        if (todoDto == null || todo == null) {
            return;
        }

        todo.setName(todoDto.getName());
        todo.setDone(todoDto.isDone());
        if (todoDto.isPrivate() != todo.getIsPrivate()) {
            todo.setIsPrivate(todoDto.isPrivate());
        }
        // Нулевые значения от старых клиентов схлопываются в дефолты прямо здесь —
        // так due_time никогда не окажется null при NOT NULL в схеме.
        todo.setDueDate(todoDto.getDueDate());
        todo.setDueTime(todoDto.getDueTime() != null ? todoDto.getDueTime() : LocalTime.of(9, 0));
        todo.setDueTimezone(todoDto.getDueTimezone());
        todo.setRemindBeforeMinutes(todoDto.getRemindBeforeMinutes() != null ? todoDto.getRemindBeforeMinutes() : 0);
        todo.setReminderScope(todoDto.getReminderScope() != null ? todoDto.getReminderScope() : ReminderScope.SELF);
    }

    /**
     * Конвертирует TodoRequest в TodoDto.
     */
    public TodoDto toDto(TodoRequest request) {
        return TodoDto.builder()
                .id(request.getId())
                .userId(request.getUserId())
                .name(request.getName())
                .done(request.getDone())
                .isPrivate(request.isPrivate())
                .clientRequestId(request.getClientRequestId())
                .listId(request.getListId())
                .dueDate(request.getDueDate())
                .dueTime(request.getDueTime())
                .dueTimezone(request.getDueTimezone())
                .remindBeforeMinutes(request.getRemindBeforeMinutes())
                .reminderScope(request.getReminderScope())
                .dueFieldsProvided(request.isDueFieldsProvided())
                .build();
    }

    /**
     * Конвертирует TodoDto в TodoResponse.
     */
    public TodoResponse toResponse(TodoDto todoDto) {
        if (todoDto == null) {
            return null;
        }

        return TodoResponse.builder()
                .id(todoDto.getId())
                .name(todoDto.getName())
                .createdAt(todoDto.getCreatedAt())
                .completedAt(todoDto.getCompletedAt())
                .done(todoDto.getDone())
                .isPrivate(todoDto.isPrivate())
                .userId(todoDto.getUserId())
                .userName(todoDto.getUserName())
                .completorUserId(todoDto.getCompletorUserId())
                .completorUserName(todoDto.getCompletorUserName())
                .listId(todoDto.getListId())
                .creatorColor(todoDto.getCreatorColor())
                .completorColor(todoDto.getCompletorColor())
                .position(todoDto.getPosition())
                .dueDate(todoDto.getDueDate())
                .dueTime(todoDto.getDueTime())
                .dueTimezone(todoDto.getDueTimezone())
                .remindBeforeMinutes(todoDto.getRemindBeforeMinutes())
                .reminderScope(todoDto.getReminderScope())
                .build();
    }
}
