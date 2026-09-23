package ru.mngerasimenko.todolist.dto.validation;

/**
 * Общие константы для валидации email-полей в DTO.
 * Используется в @Size вместо magic number 128, чтобы предел был
 * единым для всех Request/Response DTO и колонки `todo_users.email`.
 */
public final class EmailValidation {

    public static final int MAX_LENGTH = 128;

    /**
     * Ключ сообщения об ошибке для @Size — текст в {@code messages_ru/en.properties}.
     * Плейсхолдер {max} в тексте подставляется Hibernate Validator из атрибута max= аннотации.
     */
    public static final String MAX_LENGTH_MESSAGE = "{validation.email.max-length}";

    private EmailValidation() {
    }
}
