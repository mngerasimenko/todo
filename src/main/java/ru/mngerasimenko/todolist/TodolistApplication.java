package ru.mngerasimenko.todolist;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@Theme("todo-theme")
public class TodolistApplication implements AppShellConfigurator {
    public static void main(String[] args) {
        SpringApplication.run(TodolistApplication.class, args);
    }

}
