package ru.mngerasimenko.todolist.model;

/**
 * Роль пользователя внутри списка задач.
 * ADMIN — создатель списка или назначенный администратор.
 * USER — обычный участник списка.
 */
public enum TaskListRole {
    ADMIN,
    USER
}
