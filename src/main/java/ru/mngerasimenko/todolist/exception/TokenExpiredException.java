package ru.mngerasimenko.todolist.exception;

/**
 * Исключение при использовании истёкшего или невалидного токена (верификация email, сброс пароля).
 */
public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String message) {
        super(message);
    }
}
