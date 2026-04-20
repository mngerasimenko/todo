package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;
import ru.mngerasimenko.todolist.service.AdminService;

/**
 * Контроллер супер-административных операций.
 * Все методы защищены проверкой {@code @superAdminGuard.check(authentication)} —
 * email из JWT должен входить в whitelist {@code app.super-admin.emails}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("@superAdminGuard.check(authentication)")
public class AdminController {

    private final AdminService adminService;

    /**
     * Принудительно отправить напоминание о неактивности указанному пользователю.
     * Отправляет push (если есть FCM-токен) и email (если email подтверждён).
     */
    @PostMapping("/users/{email:.+}/inactive-reminder")
    public ResponseEntity<InactiveReminderTriggerResponse> triggerInactiveReminder(
            @PathVariable String email) {
        return ResponseEntity.ok(adminService.triggerInactiveReminder(email));
    }
}
