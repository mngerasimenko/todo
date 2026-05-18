package ru.mngerasimenko.todolist.dto.list;

/**
 * Один элемент в bulk-reorder запросе. Нейтральное `id` подходит и для
 * task_list_user.list_id (PATCH /api/lists/reorder), и для todo.id
 * (PATCH /api/lists/{id}/todos/reorder).
 */
public record ReorderItem(Long id, Integer position) {}
