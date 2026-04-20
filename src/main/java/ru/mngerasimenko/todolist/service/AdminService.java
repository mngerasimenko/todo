package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;

/**
 * Сервис для операций супер-администратора.
 * Все методы рассчитаны на то, что вызов уже прошёл проверку в SuperAdminGuard.
 */
public interface AdminService {

    /**
     * Принудительно отправить напоминание о неактивности конкретному пользователю.
     * В отличие от {@code InactiveReminderScheduler}, игнорирует условия "не заходил 7 дней"
     * и лимит напоминаний — используется для отладки и ручного триггера.
     *
     * @param email email пользователя
     * @return сводка: какие каналы отправлены
     * @throws ru.mngerasimenko.todolist.exception.UserNotFoundException если пользователь не найден
     */
    InactiveReminderTriggerResponse triggerInactiveReminder(String email);
}
