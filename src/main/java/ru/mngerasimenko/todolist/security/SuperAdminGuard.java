package ru.mngerasimenko.todolist.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.config.SuperAdminProperties;

import java.util.Locale;

/**
 * Guard для @PreAuthorize("@superAdminGuard.check(authentication)").
 * Возвращает true, если email (Authentication.name) входит в whitelist SuperAdminProperties.
 * Сравнение регистронезависимое.
 */
@Slf4j
@Component("superAdminGuard")
@RequiredArgsConstructor
public class SuperAdminGuard {

    private final SuperAdminProperties properties;

    @PostConstruct
    void logConfig() {
        long count = properties.getEmails().stream()
                .filter(e -> e != null && !e.isBlank())
                .count();
        if (count == 0) {
            log.warn("Super-admin whitelist is empty — /api/admin/** endpoints are closed to everyone");
        } else {
            log.info("Super-admin whitelist: {} email(s) loaded", count);
        }
    }

    public boolean check(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String email = authentication.getName();
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = email.toLowerCase(Locale.ROOT);
        return properties.getEmails().stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }
}
