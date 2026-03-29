package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException;
import ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException.LimitType;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.AppProperties;

import ru.mngerasimenko.todolist.dto.SubscriptionStatusResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private AppProperties appProperties;

    @Mock
    private Clock clock;

    @InjectMocks
    private SubscriptionServiceImpl subscriptionService;

    private User freeUser;
    private User proUser;
    private User proLifetimeUser;
    private User betaUser;
    private User expiredProUser;
    private AppProperties.SubscriptionLimits freeLimits;
    private AppProperties.SubscriptionLimits proLimits;

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(FIXED_INSTANT);
        lenient().when(clock.getZone()).thenReturn(ZONE);

        freeUser = new User();
        freeUser.setId(1L);
        freeUser.setEmail("free@test.ru");
        freeUser.setName("freeuser");
        freeUser.setSubscriptionType("FREE");

        LocalDateTime now = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);

        proUser = new User();
        proUser.setId(2L);
        proUser.setEmail("pro@test.ru");
        proUser.setName("prouser");
        proUser.setSubscriptionType("PRO");
        proUser.setSubscriptionExpiresAt(now.plusDays(30));

        proLifetimeUser = new User();
        proLifetimeUser.setId(5L);
        proLifetimeUser.setEmail("prolifetime@test.ru");
        proLifetimeUser.setName("prolifetimeuser");
        proLifetimeUser.setSubscriptionType("PRO_LIFETIME");
        proLifetimeUser.setSubscriptionExpiresAt(now.minusDays(90)); // дата активации (в прошлом)

        betaUser = new User();
        betaUser.setId(3L);
        betaUser.setEmail("beta@test.ru");
        betaUser.setName("betauser");
        betaUser.setSubscriptionType("BETA");
        betaUser.setSubscriptionExpiresAt(now.plusDays(30));
        betaUser.setBetaTester(true);

        expiredProUser = new User();
        expiredProUser.setId(4L);
        expiredProUser.setEmail("expired@test.ru");
        expiredProUser.setName("expireduser");
        expiredProUser.setSubscriptionType("PRO");
        expiredProUser.setSubscriptionExpiresAt(now.minusDays(1));

        freeLimits = new AppProperties.SubscriptionLimits(2, 30, 3, false);
        proLimits = new AppProperties.SubscriptionLimits(-1, -1, -1, true);
    }

    // --- getEffectiveSubscriptionType ---

    @Nested
    class GetEffectiveSubscriptionType {
        @Test
        void freeUser_ReturnsFree() {
            assertThat(subscriptionService.getEffectiveSubscriptionType(freeUser)).isEqualTo("FREE");
        }

        @Test
        void proUser_WithValidExpiration_ReturnsPro() {
            assertThat(subscriptionService.getEffectiveSubscriptionType(proUser)).isEqualTo("PRO");
        }

        @Test
        void betaUser_WithValidExpiration_ReturnsBeta() {
            assertThat(subscriptionService.getEffectiveSubscriptionType(betaUser)).isEqualTo("BETA");
        }

        @Test
        void proUser_WithExpiredSubscription_ReturnsFree() {
            assertThat(subscriptionService.getEffectiveSubscriptionType(expiredProUser)).isEqualTo("FREE");
        }

        @Test
        void proUser_WithNullExpiration_ReturnsFree() {
            proUser.setSubscriptionExpiresAt(null);
            assertThat(subscriptionService.getEffectiveSubscriptionType(proUser)).isEqualTo("FREE");
        }

        @Test
        void betaUser_WithNullExpiration_ReturnsFree() {
            betaUser.setSubscriptionExpiresAt(null);
            assertThat(subscriptionService.getEffectiveSubscriptionType(betaUser)).isEqualTo("FREE");
        }

        @Test
        void proLifetimeUser_ReturnsProLifetime() {
            assertThat(subscriptionService.getEffectiveSubscriptionType(proLifetimeUser)).isEqualTo("PRO_LIFETIME");
        }

        @Test
        void proLifetimeUser_WithNullExpiration_ReturnsFree() {
            proLifetimeUser.setSubscriptionExpiresAt(null);
            assertThat(subscriptionService.getEffectiveSubscriptionType(proLifetimeUser)).isEqualTo("FREE");
        }

        @Test
        void proLifetimeUser_WithPastDate_StillReturnsProLifetime() {
            proLifetimeUser.setSubscriptionExpiresAt(
                    LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).minusYears(1));
            assertThat(subscriptionService.getEffectiveSubscriptionType(proLifetimeUser)).isEqualTo("PRO_LIFETIME");
        }
    }

    // --- getLimitsForType ---

    @Nested
    class GetLimitsForType {
        @Test
        void free_ReturnsFreeLimits() {
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            assertThat(subscriptionService.getLimitsForType("FREE")).isEqualTo(freeLimits);
        }

        @Test
        void pro_ReturnsProLimits() {
            when(appProperties.getProLimits()).thenReturn(proLimits);
            assertThat(subscriptionService.getLimitsForType("PRO")).isEqualTo(proLimits);
        }

        @Test
        void beta_ReturnsProLimits() {
            when(appProperties.getProLimits()).thenReturn(proLimits);
            assertThat(subscriptionService.getLimitsForType("BETA")).isEqualTo(proLimits);
        }

        @Test
        void proLifetime_ReturnsProLimits() {
            when(appProperties.getProLimits()).thenReturn(proLimits);
            assertThat(subscriptionService.getLimitsForType("PRO_LIFETIME")).isEqualTo(proLimits);
        }
    }

    // --- assertCanCreateList ---

    @Nested
    class AssertCanCreateList {
        @Test
        void enforcementDisabled_DoesNothing() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(false);
            subscriptionService.assertCanCreateList(1L);
            verify(userRepository, never()).findById(any());
        }

        @Test
        void freeUser_BelowLimit_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(1L)).thenReturn(1L);

            subscriptionService.assertCanCreateList(1L);
        }

        @Test
        void freeUser_AtLimit_ThrowsException() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(1L)).thenReturn(2L);

            assertThatThrownBy(() -> subscriptionService.assertCanCreateList(1L))
                    .isInstanceOf(SubscriptionLimitExceededException.class)
                    .satisfies(ex -> assertThat(((SubscriptionLimitExceededException) ex).getLimitType())
                            .isEqualTo(LimitType.LIST_LIMIT));
        }

        @Test
        void proUser_Unlimited_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(2L)).thenReturn(Optional.of(proUser));
            when(appProperties.getProLimits()).thenReturn(proLimits);

            subscriptionService.assertCanCreateList(2L);
            verify(taskListUserRepository, never()).countByUserId(any());
        }

        @Test
        void expiredProUser_UsesFreeLimits() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(4L)).thenReturn(Optional.of(expiredProUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(4L)).thenReturn(2L);

            assertThatThrownBy(() -> subscriptionService.assertCanCreateList(4L))
                    .isInstanceOf(SubscriptionLimitExceededException.class);
        }

        @Test
        void nonExistentUser_ThrowsUserNotFoundException() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subscriptionService.assertCanCreateList(999L))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // --- assertCanCreateTodo ---

    @Nested
    class AssertCanCreateTodo {
        @Test
        void enforcementDisabled_DoesNothing() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(false);
            subscriptionService.assertCanCreateTodo(10L, 1L);
            verify(userRepository, never()).findById(any());
        }

        @Test
        void freeUser_BelowLimit_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(todoRepository.countByListId(10L)).thenReturn(29L);

            subscriptionService.assertCanCreateTodo(10L, 1L);
        }

        @Test
        void freeUser_AtLimit_ThrowsException() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(todoRepository.countByListId(10L)).thenReturn(30L);

            assertThatThrownBy(() -> subscriptionService.assertCanCreateTodo(10L, 1L))
                    .isInstanceOf(SubscriptionLimitExceededException.class)
                    .satisfies(ex -> assertThat(((SubscriptionLimitExceededException) ex).getLimitType())
                            .isEqualTo(LimitType.TASK_LIMIT));
        }

        @Test
        void proUser_Unlimited_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(2L)).thenReturn(Optional.of(proUser));
            when(appProperties.getProLimits()).thenReturn(proLimits);

            subscriptionService.assertCanCreateTodo(10L, 2L);
            verify(todoRepository, never()).countByListId(any());
        }
    }

    // --- assertCanJoinList ---

    @Nested
    class AssertCanJoinList {

        private TaskListUser makeAdmin(User user) {
            TaskListUser tlu = new TaskListUser();
            tlu.setUser(user);
            tlu.setRole(TaskListRole.ADMIN);
            return tlu;
        }

        @Test
        void enforcementDisabled_DoesNothing() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(false);
            subscriptionService.assertCanJoinList(10L, 1L);
            verify(taskListUserRepository, never()).findFirstByIdListIdAndRole(any(), any());
        }

        @Test
        void freeOwner_BelowLimit_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(taskListUserRepository.findFirstByIdListIdAndRole(10L, TaskListRole.ADMIN))
                    .thenReturn(Optional.of(makeAdmin(freeUser)));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByListId(10L)).thenReturn(2L);

            subscriptionService.assertCanJoinList(10L, 5L);
        }

        @Test
        void freeOwner_AtLimit_ThrowsException() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(taskListUserRepository.findFirstByIdListIdAndRole(10L, TaskListRole.ADMIN))
                    .thenReturn(Optional.of(makeAdmin(freeUser)));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByListId(10L)).thenReturn(3L);

            assertThatThrownBy(() -> subscriptionService.assertCanJoinList(10L, 5L))
                    .isInstanceOf(SubscriptionLimitExceededException.class)
                    .satisfies(ex -> assertThat(((SubscriptionLimitExceededException) ex).getLimitType())
                            .isEqualTo(LimitType.MEMBER_LIMIT));
        }

        @Test
        void proOwner_Unlimited_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(taskListUserRepository.findFirstByIdListIdAndRole(10L, TaskListRole.ADMIN))
                    .thenReturn(Optional.of(makeAdmin(proUser)));
            when(appProperties.getProLimits()).thenReturn(proLimits);

            subscriptionService.assertCanJoinList(10L, 5L);
            verify(taskListUserRepository, never()).countByListId(any());
        }

        @Test
        void noAdmin_FallsBackToJoiningUser() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(taskListUserRepository.findFirstByIdListIdAndRole(10L, TaskListRole.ADMIN))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByListId(10L)).thenReturn(1L);

            subscriptionService.assertCanJoinList(10L, 1L);
        }
    }

    // --- assertCanCreatePrivateTodo ---

    @Nested
    class AssertCanCreatePrivateTodo {
        @Test
        void enforcementDisabled_DoesNothing() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(false);
            subscriptionService.assertCanCreatePrivateTodo(1L);
            verify(userRepository, never()).findById(any());
        }

        @Test
        void freeUser_ThrowsException() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(freeUser));
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);

            assertThatThrownBy(() -> subscriptionService.assertCanCreatePrivateTodo(1L))
                    .isInstanceOf(SubscriptionLimitExceededException.class)
                    .satisfies(ex -> assertThat(((SubscriptionLimitExceededException) ex).getLimitType())
                            .isEqualTo(LimitType.PRIVATE_TASK));
        }

        @Test
        void proUser_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(2L)).thenReturn(Optional.of(proUser));
            when(appProperties.getProLimits()).thenReturn(proLimits);

            subscriptionService.assertCanCreatePrivateTodo(2L);
        }

        @Test
        void betaUser_Passes() {
            when(appProperties.isSubscriptionEnforcementEnabled()).thenReturn(true);
            when(userRepository.findById(3L)).thenReturn(Optional.of(betaUser));
            when(appProperties.getProLimits()).thenReturn(proLimits);

            subscriptionService.assertCanCreatePrivateTodo(3L);
        }
    }

    // --- getSubscriptionStatus ---

    @Nested
    class GetSubscriptionStatus {
        @Test
        void freeUser_ReturnsFreeLimits() {
            when(userRepository.getUserByEmail("free@test.ru")).thenReturn(freeUser);
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(1L)).thenReturn(1L);

            SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus("free@test.ru");

            assertThat(response.getSubscriptionType()).isEqualTo("FREE");
            assertThat(response.getSubscriptionExpiresAt()).isNull();
            assertThat(response.getLimits().getMaxLists()).isEqualTo(2);
            assertThat(response.getUsage().getListsCount()).isEqualTo(1L);
            assertThat(response.getUsage().isCanCreateList()).isTrue();
        }

        @Test
        void proUser_ReturnsProLimitsAndExpiresAt() {
            when(userRepository.getUserByEmail("pro@test.ru")).thenReturn(proUser);
            when(appProperties.getProLimits()).thenReturn(proLimits);
            when(taskListUserRepository.countByUserId(2L)).thenReturn(0L);

            SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus("pro@test.ru");

            assertThat(response.getSubscriptionType()).isEqualTo("PRO");
            assertThat(response.getSubscriptionExpiresAt()).isEqualTo(proUser.getSubscriptionExpiresAt());
            assertThat(response.getLimits().getMaxLists()).isEqualTo(-1);
        }

        @Test
        void proLifetimeUser_ReturnsProLimitsAndActivationDate() {
            when(userRepository.getUserByEmail("prolifetime@test.ru")).thenReturn(proLifetimeUser);
            when(appProperties.getProLimits()).thenReturn(proLimits);
            when(taskListUserRepository.countByUserId(5L)).thenReturn(0L);

            SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus("prolifetime@test.ru");

            assertThat(response.getSubscriptionType()).isEqualTo("PRO_LIFETIME");
            assertThat(response.getSubscriptionExpiresAt()).isEqualTo(proLifetimeUser.getSubscriptionExpiresAt());
            assertThat(response.getLimits().getMaxLists()).isEqualTo(-1);
        }

        @Test
        void expiredProUser_ReturnsFreeAndNullExpiresAt() {
            when(userRepository.getUserByEmail("expired@test.ru")).thenReturn(expiredProUser);
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(4L)).thenReturn(0L);

            SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus("expired@test.ru");

            assertThat(response.getSubscriptionType()).isEqualTo("FREE");
            assertThat(response.getSubscriptionExpiresAt()).isNull();
            assertThat(response.getLimits().getMaxLists()).isEqualTo(2);
        }

        @Test
        void nonExistentUser_ThrowsUserNotFoundException() {
            when(userRepository.getUserByEmail("unknown@test.ru")).thenReturn(null);

            assertThatThrownBy(() -> subscriptionService.getSubscriptionStatus("unknown@test.ru"))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        void freeUser_AtLimit_CanCreateListIsFalse() {
            when(userRepository.getUserByEmail("free@test.ru")).thenReturn(freeUser);
            when(appProperties.getFreeLimits()).thenReturn(freeLimits);
            when(taskListUserRepository.countByUserId(1L)).thenReturn(2L);

            SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus("free@test.ru");

            assertThat(response.getUsage().isCanCreateList()).isFalse();
        }
    }

    // --- getListsCount ---

    @Test
    void getListsCount_ReturnsCount() {
        when(taskListUserRepository.countByUserId(1L)).thenReturn(5L);
        assertThat(subscriptionService.getListsCount(1L)).isEqualTo(5L);
    }
}
