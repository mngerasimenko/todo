package ru.mngerasimenko.todolist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа Spring Boot приложения.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class TodolistApplication {
    public static void main(String[] args) {
        SpringApplication.run(TodolistApplication.class, args);
    }

}
