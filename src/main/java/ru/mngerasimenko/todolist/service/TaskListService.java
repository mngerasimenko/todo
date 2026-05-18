package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.InviteInfoResponse;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.dto.list.ReorderItem;

import java.util.List;

public interface TaskListService {

    /**
     * Создать новый список задач. Создатель автоматически получает роль ADMIN.
     */
    ListResponse createList(String name, Long creatorUserId);

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
     * Если ADMIN единственный — список удаляется целиком.
     * Если ADMIN с другими участниками — права передаются первому участнику.
     * @return описание выполненного действия
     */
    String leaveList(Long listId, Long userId);

    /**
     * Удалить список задач. Только администратор списка может выполнить удаление.
     * Удаляются все задачи, все участники и сам список.
     */
    void deleteList(Long listId, Long userId);

    /**
     * Частично обновить список задач (PATCH-семантика). Только ADMIN списка.
     * Поля {@code name} и {@code color} опциональные: {@code null} — поле не изменяется.
     * Кеш {@code task-lists} инвалидируется для всех участников списка.
     *
     * @return ответ с обновлёнными полями и ролью текущего пользователя (ADMIN)
     */
    ListResponse updateList(Long listId, Long requesterId, String name, String color);

    /**
     * Bulk-reorder списков для пользователя (per-user position).
     * Атомарно обновляет task_list_user.position. Кеш task-lists юзера инвалидируется.
     *
     * @throws IllegalArgumentException если хоть один listId не принадлежит юзеру
     */
    void reorderLists(Long userId, List<ReorderItem> items);

    /**
     * Bulk-reorder задач внутри списка (общий per-список position).
     * Любой участник списка может вызвать. Кеш todo не используется в проекте — evict не нужен.
     *
     * @throws IllegalArgumentException если юзер не участник списка, если есть дубликаты id/position,
     *                                   или если хоть один todo.id не принадлежит указанному listId
     */
    void reorderTodos(Long listId, Long requesterId, List<ReorderItem> items);

    /**
     * Создать приглашение в список. Только ADMIN списка.
     * Генерирует токен, опционально отправляет email.
     */
    InviteResponse createInvite(Long listId, Long userId, String recipientEmail);

    /**
     * Получить информацию о приглашении по токену (без авторизации).
     */
    InviteInfoResponse getInviteInfo(String token);

    /**
     * Принять приглашение по токену. Требует авторизацию.
     */
    ListResponse acceptInvite(String token, Long userId);
}
