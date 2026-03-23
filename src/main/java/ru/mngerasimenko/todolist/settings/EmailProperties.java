package ru.mngerasimenko.todolist.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация email-рассылки (отправитель, базовый URL, TTL токенов).
 */
@ConfigurationProperties(prefix = "app.email")
@Getter
@Setter
public class EmailProperties {
    private String from = "todo-noreply@mngerasimenko.ru";
    private String baseUrl = "https://todo.mngerasimenko.ru";
    private int verificationTokenTtlHours = 24;
    private int resetTokenTtlHours = 1;
    private int inviteTokenTtlHours = 24;
}
