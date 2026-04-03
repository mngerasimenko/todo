package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.dto.admin.UsageStatisticsResponse;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TaskListRepository taskListRepository;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @InjectMocks
    private StatisticsServiceImpl statisticsService;

    @Test
    void getUsageStatistics_returnsCorrectTotals() {
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(2L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(8L);
        when(taskListRepository.count()).thenReturn(5L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(1L);
        when(taskListUserRepository.countSharedLists()).thenReturn(2L);
        when(taskListUserRepository.avgMembersPerList()).thenReturn(2.4);
        when(todoRepository.count()).thenReturn(100L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(5L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(15L);
        when(todoRepository.countByDoneTrue()).thenReturn(60L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(8L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(3L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(2L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getUsers().getTotal()).isEqualTo(9); // 10 - 1 (системный пользователь)
        assertThat(result.getUsers().getNewInPeriod()).isEqualTo(2);
        assertThat(result.getUsers().getEmailVerified()).isEqualTo(8);
        assertThat(result.getLists().getTotal()).isEqualTo(5);
        assertThat(result.getLists().getNewInPeriod()).isEqualTo(1);
        assertThat(result.getLists().getSharedLists()).isEqualTo(2);
        assertThat(result.getLists().getAvgMembersPerList()).isEqualTo(2.4);
        assertThat(result.getTasks().getTotal()).isEqualTo(100);
        assertThat(result.getTasks().getNewInPeriod()).isEqualTo(15);
        assertThat(result.getTasks().getCompletedTotal()).isEqualTo(60);
        assertThat(result.getTasks().getCompletedInPeriod()).isEqualTo(8);
        assertThat(result.getTasks().getPendingTotal()).isEqualTo(40);
        assertThat(result.getTasks().getPrivateTasks()).isEqualTo(5);
        assertThat(result.getActivity().getActiveInviteTokens()).isEqualTo(2);
    }

    @Test
    void getUsageStatistics_returnsNewUserNames() {
        User user1 = new User();
        user1.setName("Иван");
        User user2 = new User();
        user2.setName("Мария");

        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(2L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of(user1, user2));
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(5L);
        when(taskListRepository.count()).thenReturn(3L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(taskListUserRepository.avgMembersPerList()).thenReturn(1.0);
        when(todoRepository.count()).thenReturn(50L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(20L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getUsers().getNewUserNames()).containsExactly("Иван", "Мария");
    }

    @Test
    void getUsageStatistics_handlesZeroUsers() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(0L);
        when(taskListRepository.count()).thenReturn(0L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(todoRepository.count()).thenReturn(0L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(0L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getUsers().getTotal()).isZero();
        assertThat(result.getUsers().getEmailVerificationRate()).isZero();
        assertThat(result.getLists().getAvgListsPerUser()).isZero();
        assertThat(result.getLists().getAvgMembersPerList()).isZero();
        assertThat(result.getTasks().getAvgTasksPerUser()).isZero();
        assertThat(result.getTasks().getAvgTasksPerList()).isZero();
        assertThat(result.getTasks().getCompletionRate()).isZero();
    }

    @Test
    void getUsageStatistics_calculatesCompletionRate() {
        when(userRepository.count()).thenReturn(5L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(5L);
        when(taskListRepository.count()).thenReturn(2L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(taskListUserRepository.avgMembersPerList()).thenReturn(1.0);
        when(todoRepository.count()).thenReturn(200L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(150L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(24);

        assertThat(result.getTasks().getCompletionRate()).isEqualTo(75.0);
        assertThat(result.getTasks().getPendingTotal()).isEqualTo(50);
    }

    @Test
    void getUsageStatistics_calculatesAverages() {
        when(userRepository.count()).thenReturn(5L); // 5 - 1 = 4 реальных
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(2L);
        when(taskListRepository.count()).thenReturn(3L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(1L);
        when(taskListUserRepository.avgMembersPerList()).thenReturn(1.7);
        when(todoRepository.count()).thenReturn(12L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(2L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(6L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getLists().getAvgListsPerUser()).isEqualTo(0.8); // 3 / 4
        assertThat(result.getTasks().getAvgTasksPerUser()).isEqualTo(3.0); // 12 / 4
        assertThat(result.getTasks().getAvgTasksPerList()).isEqualTo(4.0); // 12 / 3
        assertThat(result.getUsers().getEmailVerificationRate()).isEqualTo(50.0); // 2 / 4
    }

    @Test
    void getUsageStatistics_calculatesEmailVerificationRate() {
        when(userRepository.count()).thenReturn(4L); // 4 - 1 = 3 реальных
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(1L);
        when(taskListRepository.count()).thenReturn(0L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(todoRepository.count()).thenReturn(0L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(0L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getUsers().getEmailVerificationRate()).isEqualTo(33.3);
    }

    @Test
    void getUsageStatistics_returnsActivityMetrics() {
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(0L);
        when(taskListRepository.count()).thenReturn(0L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(todoRepository.count()).thenReturn(0L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(0L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        // countDistinctActiveUsersSince вызывается дважды — за 24ч и за 7д
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(5L, 8L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(3L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getActivity().getActiveUsersLast24h()).isEqualTo(5);
        assertThat(result.getActivity().getActiveUsersLast7d()).isEqualTo(8);
        assertThat(result.getActivity().getActiveInviteTokens()).isEqualTo(3);
    }

    @Test
    void getUsageStatistics_returnsPeriodHoursAndGeneratedAt() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(0L);
        when(taskListRepository.count()).thenReturn(0L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(todoRepository.count()).thenReturn(0L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(0L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(12);

        assertThat(result.getPeriodHours()).isEqualTo(12);
        assertThat(result.getGeneratedAt()).isNotNull();
        assertThat(result.getGeneratedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void getUsageStatistics_handlesZeroLists() {
        when(userRepository.count()).thenReturn(5L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(userRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(0L);
        when(taskListRepository.count()).thenReturn(0L);
        when(taskListRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(taskListUserRepository.countSharedLists()).thenReturn(0L);
        when(todoRepository.count()).thenReturn(10L);
        when(todoRepository.countByIsPrivateTrue()).thenReturn(0L);
        when(todoRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countByDoneTrue()).thenReturn(0L);
        when(todoRepository.countByCompletedAtAfter(any())).thenReturn(0L);
        when(todoRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);
        when(inviteTokenRepository.countByExpiresAtAfter(any())).thenReturn(0L);

        UsageStatisticsResponse result = statisticsService.getUsageStatistics(2);

        assertThat(result.getTasks().getAvgTasksPerList()).isZero();
        assertThat(result.getLists().getAvgListsPerUser()).isZero();
    }
}
