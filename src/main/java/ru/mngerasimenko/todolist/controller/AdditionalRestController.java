package ru.mngerasimenko.todolist.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.model.status.StatusAppName;
import ru.mngerasimenko.todolist.model.status.StatusTrue;

@RestController
@RequestMapping("/api")
public class AdditionalRestController {
    @GetMapping("/status")
    public StatusTrue getStatus() {
        return new StatusTrue();
    }

    @GetMapping("/appName")
    public StatusAppName getAppName() {
        return new StatusAppName();
    }

}
