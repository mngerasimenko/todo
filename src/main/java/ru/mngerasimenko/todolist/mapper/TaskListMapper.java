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
     */
    public ListResponse toResponse(TaskList taskList, TaskListRole role) {
        if (taskList == null) {
            return null;
        }
        return ListResponse.builder()
                .id(taskList.getId())
                .name(taskList.getName())
                .role(role != null ? role.name() : null)
                .createdAt(taskList.getCreatedAt() != null ? taskList.getCreatedAt().toString() : null)
                .build();
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
                .joinedAt(taskListUser.getJoinedAt() != null ? taskListUser.getJoinedAt().toString() : null)
                .build();
    }
}
