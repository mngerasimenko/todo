package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;

/**
 * Сервис для сбора статистики использования приложения.
 */
public interface StatisticsService {

    /**
     * Собирает статистику использования за указанный период.
     *
     * @param periodHours количество часов для расчёта "новых" записей
     * @return статистика использования
     */
    UsageStatisticsResponse getUsageStatistics(long periodHours);
}
