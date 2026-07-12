package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final TaskListUserRepository taskListUserRepository;
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
        long sharedLists = taskListUserRepository.countSharedLists();
        double avgMembersPerList = totalLists > 0
                ? Math.round(taskListUserRepository.avgMembersPerList() * 10.0) / 10.0
                : 0.0;

        // Задачи
        long totalTasks = todoRepository.count();
        long newTasks = todoRepository.countByCreatedAtAfter(since);
        long privateTasks = todoRepository.countByIsPrivateTrue();
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

        // Активность — объединение DISTINCT user_id из 4 источников:
        // создал/выполнил Todo, создал TaskList, вступил в TaskListUser
        long activeUsersLast24h = countActiveUsersSince(now.minusHours(24));
        long activeUsersLast3d = countActiveUsersSince(now.minusDays(3));
        long activeUsersLast7d = countActiveUsersSince(now.minusDays(7));
        long activeUsersLast30d = countActiveUsersSince(now.minusDays(30));
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
                        .sharedLists(sharedLists)
                        .avgMembersPerList(avgMembersPerList)
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
                        .privateTasks(privateTasks)
                        .build())
                .activity(UsageStatisticsResponse.ActivityStats.builder()
                        .activeUsersLast24h(activeUsersLast24h)
                        .activeUsersLast3d(activeUsersLast3d)
                        .activeUsersLast7d(activeUsersLast7d)
                        .activeUsersLast30d(activeUsersLast30d)
                        .activeInviteTokens(activeInviteTokens)
                        .build())
                .build();
    }

    /**
     * Считает уникальных активных пользователей за период.
     * Активным считается пользователь, который за указанный интервал:
     *  — создал или выполнил задачу,
     *  — создал список,
     *  — вступил в список.
     */
    private long countActiveUsersSince(LocalDateTime since) {
        Set<Long> ids = new HashSet<>(todoRepository.findActiveUserIdsSince(since));
        ids.addAll(taskListRepository.findActiveUserIdsSince(since));
        ids.addAll(taskListUserRepository.findActiveUserIdsSince(since));
        return ids.size();
    }
}
