package ru.mngerasimenko.todolist.exception;

/**
 * Исключение: попытка заблокировать строку, которой нет в глобальном словаре подсказок
 * (Server R-6). Обрабатывается {@link GlobalExceptionHandler} как HTTP 404 в формате,
 * единообразном с остальными {@code *NotFoundException}'ами проекта.
 */
public class SuggestionNotFoundException extends RuntimeException {
    public SuggestionNotFoundException(String message) {
        super(message);
    }
}
