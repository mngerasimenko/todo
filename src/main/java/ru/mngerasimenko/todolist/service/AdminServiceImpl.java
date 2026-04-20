package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserService userService;
    private final EmailService emailService;
    private final PushNotificationService pushNotificationService;

    @Override
    public InactiveReminderTriggerResponse triggerInactiveReminder(String email) {
        UserDto user = userService.getUserByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("Пользователь с email " + email + " не найден");
        }

        boolean pushSent = false;
        try {
            pushNotificationService.sendInactiveReminderPush(user.getId(), user.getName());
            pushSent = true;
        } catch (Exception e) {
            log.warn("[admin] Ошибка отправки push userId={}: {}", user.getId(), e.getMessage());
        }

        boolean emailSent = false;
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            try {
                emailService.sendInactiveReminderEmail(user.getEmail(), user.getName(), user.getId());
                emailSent = true;
            } catch (Exception e) {
                log.warn("[admin] Ошибка отправки email userId={}: {}", user.getId(), e.getMessage());
            }
        }

        try {
            userService.markReminderSent(user.getId());
        } catch (Exception e) {
            log.warn("[admin] Ошибка отметки напоминания userId={}: {}", user.getId(), e.getMessage());
        }

        log.info("[admin] Триггер напоминания userId={} push={} email={}", user.getId(), pushSent, emailSent);

        return InactiveReminderTriggerResponse.builder()
                .userId(user.getId())
                .pushSent(pushSent)
                .emailSent(emailSent)
                .build();
    }
}
