package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.AppTodoResponse;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.RedisHealthService;
import ru.mngerasimenko.todolist.settings.AppProperties;
import ru.mngerasimenko.todolist.settings.Constants;

/**
 * REST-контроллер для служебных эндпоинтов приложения.
 * Предоставляет статус сервера, версию и название приложения.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AppRestController {

    private final AppProperties appProperties;
    private final EmailService emailService;
    private final PushNotificationService pushNotificationService;
    private final RedisHealthService redisHealthService;

    /** Статус приложения: версия сервера, минимальная версия Android-клиента, здоровье SMTP/Firebase/Redis */
    @GetMapping("/status")
    public ResponseEntity<AppTodoResponse> getStatus() {
        AppTodoResponse response = AppTodoResponse.builder()
                .status(true)
                .version(appProperties.getVersion())
                .minAndroidVersion(appProperties.getMinAndroidVersion())
                .latestAndroidVersion(appProperties.getLatestAndroidVersion())
                .smtpHealthy(emailService.isSmtpHealthy())
                .firebaseHealthy(pushNotificationService.isFirebaseHealthy())
                .redisHealthy(redisHealthService.isRedisHealthy())
                .build();
        return ResponseEntity.ok(response);
    }

    /** Название приложения */
    @GetMapping("/appName")
    public ResponseEntity<AppTodoResponse> getAppName() {
        AppTodoResponse response = AppTodoResponse.builder()
                .appName(Constants.APP_NAME)
                .build();
        return ResponseEntity.ok(response);
    }

}
