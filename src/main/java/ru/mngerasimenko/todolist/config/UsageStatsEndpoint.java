package ru.mngerasimenko.todolist.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;
import ru.mngerasimenko.todolist.service.StatisticsService;

/**
 * Actuator-эндпоинт для статистики использования приложения.
 * Доступен на management-порту: http://localhost:8091/actuator/usagestats
 */
@Component
@Endpoint(id = "usagestats")
@RequiredArgsConstructor
public class UsageStatsEndpoint {

    private static final long DEFAULT_PERIOD_HOURS = 2;

    private final StatisticsService statisticsService;

    /**
     * Получить статистику за период по умолчанию (2 часа).
     */
    @ReadOperation
    public UsageStatisticsResponse getStats() {
        return statisticsService.getUsageStatistics(DEFAULT_PERIOD_HOURS);
    }

    /**
     * Получить статистику за указанный период (в часах).
     */
    @ReadOperation
    public UsageStatisticsResponse getStatsByPeriod(@Selector String periodHours) {
        long hours;
        try {
            hours = Long.parseLong(periodHours);
            if (hours <= 0) {
                hours = DEFAULT_PERIOD_HOURS;
            }
        } catch (NumberFormatException e) {
            hours = DEFAULT_PERIOD_HOURS;
        }
        return statisticsService.getUsageStatistics(hours);
    }
}
