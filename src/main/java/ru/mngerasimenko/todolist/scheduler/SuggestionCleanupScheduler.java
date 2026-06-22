package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

/**
 * Еженедельный cleanup глобального словаря подсказок (Server R-6).
 * <p>
 * Удаляет записи, не использовавшиеся более {@code app.suggestions.cleanup-days} дней
 * (по умолчанию 365). Защищает таблицу от неограниченного роста: типичный пользователь
 * генерирует ~10 уникальных строк в месяц, за год это десятки тысяч на всё население —
 * без cleanup'а данные будут копиться годами.
 *
 * <p><b>TODO (panel-review concurrency#2, 2026-06-21):</b> при появлении второй
 * реплики todo-app против общей prod-БД здесь нужен распределённый lock
 * (ShedLock / pg_advisory_lock / выборка с {@code FOR UPDATE SKIP LOCKED}).
 * Сейчас prod = одна реплика, проблема не материализована, индекс
 * {@code idx_task_suggestion_last_used_at} (025c) делает DELETE дешёвым.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionCleanupScheduler {

    private final TaskSuggestionRepository repository;
    private final SuggestionProperties properties;
    private final FeatureFlagStore flagStore;

    /**
     * Каждое воскресенье в 04:00 UTC (после ночного backup'а в 03:00, до начала европейского дня).
     */
    @Scheduled(cron = "0 0 4 * * SUN", zone = "UTC")
    @Transactional
    public void cleanup() {
        if (!flagStore.isEnabled(FeatureFlag.SUGGESTIONS)) {
            log.debug("[suggestion-cleanup] Scheduler пропускает итерацию: SUGGESTIONS=false");
            return;
        }
        int days = properties.getCleanupDays();
        try {
            int deleted = repository.deleteOlderThanDays(days);
            log.info("[suggestion-cleanup] Удалено записей словаря старше {} дней: {}", days, deleted);
        } catch (RuntimeException ex) {
            log.warn("[suggestion-cleanup] Ошибка cleanup'а словаря: {}", ex.toString());
        }
    }
}
