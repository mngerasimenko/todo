package ru.mngerasimenko.todolist.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.scheduler.InactiveReminderScheduler;

import java.util.Map;

/**
 * Actuator-эндпоинт для ручного запуска напоминаний неактивным пользователям.
 * Доступен только на management-порту: http://localhost:8091/actuator/triggerreminder
 */
@Component
@Endpoint(id = "triggerreminder")
@RequiredArgsConstructor
@ConditionalOnBean(InactiveReminderScheduler.class)
public class TriggerReminderEndpoint {

    private final InactiveReminderScheduler scheduler;

    @ReadOperation
    public Map<String, String> trigger() {
        scheduler.sendReminders();
        return Map.of("status", "done", "message", "Inactive reminder triggered");
    }
}
