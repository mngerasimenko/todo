package ru.mngerasimenko.todolist.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.admin.FeatureFlagResponse;
import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;
import ru.mngerasimenko.todolist.exception.SuggestionNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.service.AdminService;
import ru.mngerasimenko.todolist.service.SuggestionService;

import java.util.List;

/**
 * Контроллер супер-административных операций.
 * Все методы защищены проверкой {@code @superAdminGuard.check(authentication)} —
 * email из JWT должен входить в whitelist {@code app.super-admin.emails}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("@superAdminGuard.check(authentication)")
public class AdminController {

    private final AdminService adminService;
    private final FeatureFlagStore flagStore;
    private final SuggestionService suggestionService;

    /**
     * Принудительно отправить напоминание о неактивности указанному пользователю.
     * Отправляет push (если есть FCM-токен) и email (если email подтверждён).
     */
    @PostMapping("/users/{email:.+}/inactive-reminder")
    public ResponseEntity<InactiveReminderTriggerResponse> triggerInactiveReminder(
            @PathVariable String email) {
        return ResponseEntity.ok(adminService.triggerInactiveReminder(email));
    }

    /** Список всех известных feature-флагов с текущими значениями и источниками. */
    @GetMapping("/flags")
    public ResponseEntity<List<FeatureFlagResponse>> listFlags() {
        List<FeatureFlagResponse> result = flagStore.snapshot().entrySet().stream()
                .map(e -> FeatureFlagResponse.builder()
                        .name(e.getKey().getName())
                        .enabled(e.getValue().value())
                        .defaultValue(e.getKey().getDefaultValue())
                        .source(e.getValue().source().name())
                        .description(e.getKey().getDescription())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Установить runtime-override для флага. {@code value} принимает {@code true} / {@code false}.
     * Любое другое значение → 400 (MethodArgumentTypeMismatchException).
     * Неизвестное имя флага → 404 (FeatureFlagNotFoundException, маскируется в GlobalExceptionHandler).
     */
    @PutMapping("/flags/{name}/{value}")
    public ResponseEntity<Void> setFlag(@PathVariable String name, @PathVariable boolean value,
                                        Authentication authentication) {
        FeatureFlag flag = FeatureFlag.findByName(name)
                .orElseThrow(() -> new FeatureFlagNotFoundException(name));
        flagStore.set(flag, value);
        log.info("[admin] {} set feature flag {}={}", authentication.getName(), name, value);
        return ResponseEntity.noContent().build();
    }

    /**
     * Сбросить runtime-override — при следующем запросе флага используется
     * значение из env или enum-default.
     */
    @DeleteMapping("/flags/{name}")
    public ResponseEntity<Void> resetFlag(@PathVariable String name, Authentication authentication) {
        FeatureFlag flag = FeatureFlag.findByName(name)
                .orElseThrow(() -> new FeatureFlagNotFoundException(name));
        flagStore.reset(flag);
        log.info("[admin] {} reset feature flag {}", authentication.getName(), name);
        return ResponseEntity.noContent().build();
    }

    /**
     * Заблокировать строку в глобальном словаре подсказок (Server R-6).
     * Запись не удаляется, только проставляется {@code blocked = true} — частота сохраняется
     * на случай разблокировки. Кеш подсказок инвалидируется внутри сервиса.
     * <p>
     * Текст принимается через {@code @PathVariable} с regex-суффиксом {@code :.+},
     * чтобы пропустить точки/пробелы/кириллицу/спецсимволы (тот же приём что для email
     * в {@link #triggerInactiveReminder}). Не-найденная строка отдаёт стандартный
     * 404-JSON через {@link SuggestionNotFoundException} + GlobalExceptionHandler,
     * а не пустое тело — единый формат с остальными 404 в проекте.
     *
     * @return 204 при успехе, 404 если такой строки в словаре нет
     */
    @PostMapping("/suggestions/{text:.+}/block")
    public ResponseEntity<Void> blockSuggestion(@PathVariable("text") String text,
                                                Authentication authentication) {
        boolean blocked = suggestionService.block(text);
        if (!blocked) {
            log.info("[admin] {} попытался заблокировать неизвестную строку словаря (len={})",
                    authentication.getName(), text == null ? 0 : text.length());
            throw new SuggestionNotFoundException("Suggestion not found in dictionary");
        }
        log.info("[admin] {} заблокировал строку словаря (len={})",
                authentication.getName(), text.length());
        return ResponseEntity.noContent().build();
    }
}
