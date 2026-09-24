package ru.mngerasimenko.todolist.exception;

/**
 * Вход не состоялся по вине инфраструктуры (БД, расшифровка), а не учётных данных.
 * <p>
 * Отдельный тип нужен, чтобы такой отказ не смешивался с неверным паролем: Spring Security
 * заворачивает любое падение {@code UserDetailsService} в {@code AuthenticationException},
 * и без разделения упавшая БД выглядела бы для пользователя как «неверный email или пароль».
 * Сообщение приходит уже локализованным — причина остаётся в логе, наружу не уходит.
 */
public class AuthServiceUnavailableException extends RuntimeException {
    public AuthServiceUnavailableException(String message) {
        super(message);
    }
}
