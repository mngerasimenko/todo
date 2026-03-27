package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.SubscriptionStatusResponse;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.settings.AppProperties;

/**
 * Сервис проверки лимитов подписки.
 * Определяет эффективный тип подписки и проверяет лимиты перед созданием ресурсов.
 */
public interface SubscriptionService {

    /**
     * Проверяет, может ли пользователь создать новый список.
     * @throws ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException при превышении лимита
     */
    void assertCanCreateList(Long userId);

    /**
     * Проверяет, может ли пользователь создать задачу в списке.
     * @throws ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException при превышении лимита
     */
    void assertCanCreateTodo(Long listId, Long userId);

    /**
     * Проверяет, может ли пользователь вступить в список (лимит участников).
     * @throws ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException при превышении лимита
     */
    void assertCanJoinList(Long listId, Long userId);

    /**
     * Проверяет, может ли пользователь создать приватную задачу.
     * @throws ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException при превышении лимита
     */
    void assertCanCreatePrivateTodo(Long userId);

    /**
     * Определяет эффективный тип подписки с учётом срока действия.
     * Если подписка истекла — возвращает FREE.
     */
    String getEffectiveSubscriptionType(User user);

    /**
     * Возвращает лимиты для указанного типа подписки.
     */
    AppProperties.SubscriptionLimits getLimitsForType(String subscriptionType);

    /**
     * Количество списков пользователя.
     */
    long getListsCount(Long userId);

    /**
     * Возвращает полный статус подписки пользователя по email (из JWT).
     */
    SubscriptionStatusResponse getSubscriptionStatus(String email);
}
