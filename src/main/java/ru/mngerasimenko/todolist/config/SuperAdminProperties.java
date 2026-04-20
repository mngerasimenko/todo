package ru.mngerasimenko.todolist.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Список email-адресов супер-администраторов.
 * Источник: property {@code app.super-admin.emails} (CSV) или env {@code SUPER_ADMIN_EMAILS}.
 * Проверка email идёт в нижнем регистре.
 */
@Component
@ConfigurationProperties(prefix = "app.super-admin")
@Getter
@Setter
public class SuperAdminProperties {

    private List<String> emails = Collections.emptyList();
}
