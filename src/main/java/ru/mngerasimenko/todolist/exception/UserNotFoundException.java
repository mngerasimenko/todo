package ru.mngerasimenko.todolist.exception;

/**
 * Исключение: пользователь не найден (HTTP 404).
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String message) {
        super(message);
    }
}
