package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;

/**
 * Маппер для конвертации TaskList и TaskListUser в DTO ответов.
 */
@Component
public class TaskListMapper {

    /**
     * Конвертация списка задач в ответ с ролью текущего пользователя.
     * {@code position} — персональная позиция списка у текущего юзера (per-user sorting).
     * Допустимо передавать {@code null} в single-list endpoints (createList/updateList/acceptInvite),
     * где клиент не использует это поле.
     */
    public ListResponse toResponse(TaskList taskList, TaskListRole role, Integer position) {
        if (taskList == null) {
            return null;
        }
        return ListResponse.builder()
                .id(taskList.getId())
                .name(taskList.getName())
                .color(taskList.getColor())
                .position(position)
                .creatorName(taskList.getCreator() != null ? taskList.getCreator().getName() : null)
                .role(role != null ? role.name() : null)
                .createdAt(taskList.getCreatedAt())
                .build();
    }

    /**
     * Перегрузка без {@code position} — для single-list ответов (create/update/accept),
     * где position не передаётся клиенту.
     */
    public ListResponse toResponse(TaskList taskList, TaskListRole role) {
        return toResponse(taskList, role, null);
    }

    /**
     * Конвертация участника списка в ответ.
     */
    public ListMemberResponse toMemberResponse(TaskListUser taskListUser) {
        if (taskListUser == null) {
            return null;
        }
        return ListMemberResponse.builder()
                .userId(taskListUser.getUser() != null ? taskListUser.getUser().getId() : null)
                .userName(taskListUser.getUser() != null ? taskListUser.getUser().getName() : null)
                .role(taskListUser.getRole() != null ? taskListUser.getRole().name() : null)
                .joinedAt(taskListUser.getJoinedAt())
                .build();
    }
}
