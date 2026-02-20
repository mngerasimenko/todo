package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;

import java.util.List;

public interface TaskListService {

    /**
     * Создать новый список задач. Создатель автоматически получает роль ADMIN.
     */
    ListResponse createList(String name, String password, Long creatorUserId);

    /**
     * Вступить в существующий список по названию и паролю.
     */
    ListResponse joinList(String name, String password, Long userId);

    /**
     * Получить списки задач текущего пользователя.
     */
    List<ListResponse> getListsByUserId(Long userId);

    /**
     * Получить список участников списка задач.
     */
    List<ListMemberResponse> getMembers(Long listId, Long requestingUserId);

    /**
     * Получить задачи списка (с учётом приватности).
     */
    List<TodoDto> getTodosByList(Long listId, Long requestingUserId);

    /**
     * Выйти из списка. Приватные задачи пользователя в списке удаляются.
     */
    void leaveList(Long listId, Long userId);
}
