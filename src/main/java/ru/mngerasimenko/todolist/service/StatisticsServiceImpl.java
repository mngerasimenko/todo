package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Реализация сервиса статистики использования.
 * Вычисляет метрики on-the-fly из существующих данных.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository userRepository;
    private final TodoRepository todoRepository;
    private final TaskListRepository taskListRepository;
    private final InviteTokenRepository inviteTokenRepository;

    @Override
    @Transactional(readOnly = true)
    public UsageStatisticsResponse getUsageStatistics(long periodHours) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(periodHours);

        // Пользователи (минус системный пользователь id=0)
        long totalUsers = Math.max(0, userRepository.count() - 1);
        long newUsers = userRepository.countByCreatedAtAfter(since);
        List<String> newUserNames = userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since)
                .stream()
                .map(User::getName)
                .toList();
        long emailVerified = userRepository.countByEmailVerifiedTrue();
        double emailVerificationRate = totalUsers > 0
                ? Math.round((double) emailVerified / totalUsers * 1000.0) / 10.0
                : 0.0;

        // Списки
        long totalLists = taskListRepository.count();
        long newLists = taskListRepository.countByCreatedAtAfter(since);
        double avgListsPerUser = totalUsers > 0
                ? Math.round((double) totalLists / totalUsers * 10.0) / 10.0
                : 0.0;

        // Задачи
        long totalTasks = todoRepository.count();
        long newTasks = todoRepository.countByCreatedAtAfter(since);
        long completedTotal = todoRepository.countByDoneTrue();
        long completedInPeriod = todoRepository.countByCompletedAtAfter(since);
        long pendingTotal = totalTasks - completedTotal;
        double completionRate = totalTasks > 0
                ? Math.round((double) completedTotal / totalTasks * 1000.0) / 10.0
                : 0.0;
        double avgTasksPerUser = totalUsers > 0
                ? Math.round((double) totalTasks / totalUsers * 10.0) / 10.0
                : 0.0;
        double avgTasksPerList = totalLists > 0
                ? Math.round((double) totalTasks / totalLists * 10.0) / 10.0
                : 0.0;

        // Активность
        long activeUsersLast24h = todoRepository.countDistinctActiveUsersSince(now.minusHours(24));
        long activeUsersLast7d = todoRepository.countDistinctActiveUsersSince(now.minusDays(7));
        long activeInviteTokens = inviteTokenRepository.countByExpiresAtAfter(now);

        return UsageStatisticsResponse.builder()
                .generatedAt(now.format(FORMATTER))
                .periodHours(periodHours)
                .users(UsageStatisticsResponse.UserStats.builder()
                        .total(totalUsers)
                        .newInPeriod(newUsers)
                        .newUserNames(newUserNames)
                        .emailVerified(emailVerified)
                        .emailVerificationRate(emailVerificationRate)
                        .build())
                .lists(UsageStatisticsResponse.ListStats.builder()
                        .total(totalLists)
                        .newInPeriod(newLists)
                        .avgListsPerUser(avgListsPerUser)
                        .build())
                .tasks(UsageStatisticsResponse.TaskStats.builder()
                        .total(totalTasks)
                        .newInPeriod(newTasks)
                        .completedTotal(completedTotal)
                        .completedInPeriod(completedInPeriod)
                        .pendingTotal(pendingTotal)
                        .completionRate(completionRate)
                        .avgTasksPerUser(avgTasksPerUser)
                        .avgTasksPerList(avgTasksPerList)
                        .build())
                .activity(UsageStatisticsResponse.ActivityStats.builder()
                        .activeUsersLast24h(activeUsersLast24h)
                        .activeUsersLast7d(activeUsersLast7d)
                        .activeInviteTokens(activeInviteTokens)
                        .build())
                .build();
    }
}
