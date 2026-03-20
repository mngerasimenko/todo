package ru.mngerasimenko.todolist.exception;

/**
 * Исключение при превышении лимита подписки.
 * Содержит тип лимита для информирования клиента.
 */
public class SubscriptionLimitExceededException extends RuntimeException {

    private final LimitType limitType;

    public SubscriptionLimitExceededException(String message, LimitType limitType) {
        super(message);
        this.limitType = limitType;
    }

    public LimitType getLimitType() {
        return limitType;
    }

    /**
     * Типы лимитов подписки.
     */
    public enum LimitType {
        LIST_LIMIT,
        TASK_LIMIT,
        MEMBER_LIMIT,
        PRIVATE_TASK
    }
}
