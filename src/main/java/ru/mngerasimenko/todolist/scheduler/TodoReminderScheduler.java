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
     * Каждую минуту: срок «18:00» означает доставку в промежутке 18:00–18:01.
     *
     * Было 5 минут — уменьшено 06.09.2026 по наблюдению владельца на проде: метка просрочки
     * краснеет мгновенно (её считает клиент), а push ждал ближайшего прохода, и разрыв до пяти
     * минут читался как «уведомление не пришло». При запасе «в момент срока» это заметно.
     * Цена шага мала: запрос идёт по проиндексированным полям и отбирает единицы строк
     * (на проде 06.09 их было 4), пустой проход не пишет в лог и ничего не рассылает.
     * Но с ростом числа задач это перестанет быть бесплатным — см. plans.md, задача про
     * анализ нагрузки планировщика.
     *
     * Интервал вынесен в property по образцу SmtpHealthScheduler и UsageStatsScheduler —
     * его можно поменять без пересборки, задав {@code app.todo-reminder.interval-ms}
     * (env {@code APP_TODO_REMINDER_INTERVAL_MS}), если понадобится откатить шаг обратно.
     * Именно fixedDelay, а не fixedRate: следующий проход стартует через интервал ПОСЛЕ
     * завершения предыдущего, поэтому долгая рассылка не наслаивается сама на себя — при
     * минутном шаге это важнее, чем при пятиминутном.
     */
    @Scheduled(fixedDelayString = "${app.todo-reminder.interval-ms:60000}")
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
