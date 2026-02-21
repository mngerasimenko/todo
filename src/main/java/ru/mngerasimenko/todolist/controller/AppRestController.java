package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.AppTodoResponse;
import ru.mngerasimenko.todolist.settings.AppProperties;
import ru.mngerasimenko.todolist.settings.Constants;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AppRestController {

    private final AppProperties appProperties;

    @GetMapping("/status")
    public ResponseEntity<AppTodoResponse> getStatus() {
        AppTodoResponse response = AppTodoResponse.builder()
                .status(true)
                .version(appProperties.getVersion())
                .minAndroidVersion(appProperties.getMinAndroidVersion())
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/appName")
    public ResponseEntity<AppTodoResponse> getAppName() {
        AppTodoResponse response = AppTodoResponse.builder()
                .appName(Constants.APP_NAME)
                .build();
        return ResponseEntity.ok(response);
    }

}
