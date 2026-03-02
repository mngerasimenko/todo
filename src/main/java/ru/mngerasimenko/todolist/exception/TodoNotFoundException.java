package ru.mngerasimenko.todolist.exception;

/**
 * Исключение: задача не найдена (HTTP 404).
 */
public class TodoNotFoundException extends RuntimeException {
    public TodoNotFoundException(String message) {
        super(message);
    }
}
