package ru.mngerasimenko.todolist.exception;

/**
 * Исключение: список задач не найден (HTTP 404).
 */
public class ListNotFoundException extends RuntimeException {
    public ListNotFoundException(String message) {
        super(message);
    }
}
