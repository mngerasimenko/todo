package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;
import ru.mngerasimenko.todolist.service.StatisticsService;

/**
 * Периодическое логирование статистики использования.
 * По умолчанию — каждые 2 часа.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UsageStatsScheduler {

    private static final long DEFAULT_PERIOD_HOURS = 2;

    private final StatisticsService statisticsService;

    @Scheduled(fixedRateString = "${app.stats.interval-ms:7200000}")
    public void logUsageStats() {
        UsageStatisticsResponse stats = statisticsService.getUsageStatistics(DEFAULT_PERIOD_HOURS);
        log.info("Статистика использования (за {}ч): пользователей={} (новых={}), списков={} (новых={}), " +
                        "задач={} (новых={}), выполнено={} ({}%), активных за 24ч={}, за 3д={}, за 7д={}, за 30д={}",
                stats.getPeriodHours(),
                stats.getUsers().getTotal(), stats.getUsers().getNewInPeriod(),
                stats.getLists().getTotal(), stats.getLists().getNewInPeriod(),
                stats.getTasks().getTotal(), stats.getTasks().getNewInPeriod(),
                stats.getTasks().getCompletedTotal(), stats.getTasks().getCompletionRate(),
                stats.getActivity().getActiveUsersLast24h(),
                stats.getActivity().getActiveUsersLast3d(),
                stats.getActivity().getActiveUsersLast7d(),
                stats.getActivity().getActiveUsersLast30d());
    }
}
