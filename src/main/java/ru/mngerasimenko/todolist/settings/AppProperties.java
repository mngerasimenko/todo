package ru.mngerasimenko.todolist.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String version = "0.0.1";
    private int minAndroidVersion = 1;
}
