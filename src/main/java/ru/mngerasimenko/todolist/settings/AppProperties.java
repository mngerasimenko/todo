package ru.mngerasimenko.todolist.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Конфигурация приложения (версия, минимальная версия Android, CORS origins).
 */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String version = "0.0.1";
    private int minAndroidVersion = 1;
    private List<String> corsOrigins = List.of();
}
