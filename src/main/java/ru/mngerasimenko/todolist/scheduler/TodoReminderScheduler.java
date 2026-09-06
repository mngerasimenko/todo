package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.service.TodoService;

/**
 * Планировщик рассылки напоминаний о сроках задач.
 * Единственная обязанность планировщика — проверить feature-флаг и дёрнуть сервис;
 * вся логика (кому, каким каналом, что при сбое) живёт в TodoServiceImpl.dispatchDueReminders,
 * чтобы её можно было протестировать и вызвать вручную (см. TriggerReminderEndpoint по образцу).
 *
 * Флагов ДВА, и они разного уровня. {@link FeatureFlag#TODO_REMINDERS} гасит весь свип и
 * проверяется здесь. {@link FeatureFlag#TODO_REMINDER_EMAIL} гасит только канал писем внутри
 * свипа и проверяется в TodoServiceImpl.dispatchDueReminders — это позволяет запустить
 * напоминания push'ами, не трогая согласия живых пользователей. Оба по умолчанию выключены и
 * включаются вручную после проверки на staging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoReminderScheduler {

    private final TodoService todoService;
    private final FeatureFlagStore flagStore;

    /**
     * Каждые 5 минут: срок «18:00» означает доставку в промежутке 18:00–18:05.
     * Интервал вынесен в property по образцу SmtpHealthScheduler и UsageStatsScheduler —
     * его можно поменять без пересборки. Именно fixedDelay, а не fixedRate: следующий
     * проход стартует через 5 минут ПОСЛЕ завершения предыдущего, поэтому долгая
     * рассылка не наслаивается сама на себя.
     */
    @Scheduled(fixedDelayString = "${app.todo-reminder.interval-ms:300000}")
    public void dispatch() {
        if (!flagStore.isEnabled(FeatureFlag.TODO_REMINDERS)) {
            log.debug("[todo-reminder] Scheduler отключён через feature flag");
            return;
        }
        int processed = todoService.dispatchDueReminders();
        if (processed > 0) {
            log.info("[todo-reminder] Обработано задач: {}", processed);
        }
    }
}
