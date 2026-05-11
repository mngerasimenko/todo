package ru.mngerasimenko.todolist.dto.validation;

/**
 * Общие константы для валидации email-полей в DTO.
 * Используется в @Size вместо magic number 128, чтобы предел был
 * единым для всех Request/Response DTO и колонки `todo_users.email`.
 */
public final class EmailValidation {

    public static final int MAX_LENGTH = 128;

    /**
     * Сообщение об ошибке для @Size. Плейсхолдер {max} подставляется
     * Hibernate Validator из атрибута max= аннотации.
     */
    public static final String MAX_LENGTH_MESSAGE = "Email must not exceed {max} characters";

    private EmailValidation() {
    }
}
