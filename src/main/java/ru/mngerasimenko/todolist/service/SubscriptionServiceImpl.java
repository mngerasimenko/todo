package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.SubscriptionStatusResponse;
import ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException;
import ru.mngerasimenko.todolist.exception.SubscriptionLimitExceededException.LimitType;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.AppProperties;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Реализация сервиса проверки лимитов подписки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final UserRepository userRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final TodoRepository todoRepository;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    public void assertCanCreateList(Long userId) {
        if (!appProperties.isSubscriptionEnforcementEnabled()) {
            return;
        }

        User user = findUser(userId);
        AppProperties.SubscriptionLimits limits = getLimitsForUser(user);

        if (limits.getMaxLists() == -1) {
            return;
        }

        long currentCount = taskListUserRepository.countByUserId(userId);
        if (currentCount >= limits.getMaxLists()) {
            throw new SubscriptionLimitExceededException(
                    "Превышен лимит списков: максимум " + limits.getMaxLists() +
                            " для подписки " + getEffectiveSubscriptionType(user),
                    LimitType.LIST_LIMIT);
        }
    }

    @Override
    public void assertCanCreateTodo(Long listId, Long userId) {
        if (!appProperties.isSubscriptionEnforcementEnabled()) {
            return;
        }

        User user = findUser(userId);
        AppProperties.SubscriptionLimits limits = getLimitsForUser(user);

        if (limits.getMaxTasksPerList() == -1) {
            return;
        }

        long currentCount = todoRepository.countByListId(listId);
        if (currentCount >= limits.getMaxTasksPerList()) {
            throw new SubscriptionLimitExceededException(
                    "Превышен лимит задач в списке: максимум " + limits.getMaxTasksPerList() +
                            " для подписки " + getEffectiveSubscriptionType(user),
                    LimitType.TASK_LIMIT);
        }
    }

    @Override
    public void assertCanJoinList(Long listId, Long userId) {
        if (!appProperties.isSubscriptionEnforcementEnabled()) {
            return;
        }

        // Лимит участников определяется подпиской администратора списка, а не вступающего
        User listOwner = taskListUserRepository.findFirstByIdListIdAndRole(listId, TaskListRole.ADMIN)
                .map(tlu -> tlu.getUser())
                .orElseGet(() -> findUser(userId));

        AppProperties.SubscriptionLimits limits = getLimitsForUser(listOwner);

        if (limits.getMaxMembersPerList() == -1) {
            return;
        }

        long currentCount = taskListUserRepository.countByListId(listId);
        if (currentCount >= limits.getMaxMembersPerList()) {
            throw new SubscriptionLimitExceededException(
                    "Превышен лимит участников в списке: максимум " + limits.getMaxMembersPerList() +
                            " для подписки " + getEffectiveSubscriptionType(listOwner),
                    LimitType.MEMBER_LIMIT);
        }
    }

    @Override
    public void assertCanCreatePrivateTodo(Long userId) {
        if (!appProperties.isSubscriptionEnforcementEnabled()) {
            return;
        }

        User user = findUser(userId);
        AppProperties.SubscriptionLimits limits = getLimitsForUser(user);

        if (!limits.isPrivateTasksAllowed()) {
            throw new SubscriptionLimitExceededException(
                    "Приватные задачи недоступны для подписки " + getEffectiveSubscriptionType(user),
                    LimitType.PRIVATE_TASK);
        }
    }

    @Override
    public String getEffectiveSubscriptionType(User user) {
        String type = user.getSubscriptionType();
        if ("FREE".equals(type)) {
            return "FREE";
        }

        // PRO и BETA — проверяем срок действия
        if (user.getSubscriptionExpiresAt() != null
                && user.getSubscriptionExpiresAt().isBefore(LocalDateTime.now(clock))) {
            return "FREE";
        }

        return type;
    }

    @Override
    public AppProperties.SubscriptionLimits getLimitsForType(String subscriptionType) {
        if ("PRO".equals(subscriptionType) || "BETA".equals(subscriptionType)) {
            return appProperties.getProLimits();
        }
        return appProperties.getFreeLimits();
    }

    @Override
    public long getListsCount(Long userId) {
        return taskListUserRepository.countByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getSubscriptionStatus(String email) {
        User user = userRepository.getUserByEmail(email.toLowerCase());
        if (user == null) {
            throw new UserNotFoundException("User not found: " + email);
        }

        String effectiveType = getEffectiveSubscriptionType(user);
        AppProperties.SubscriptionLimits limits = getLimitsForType(effectiveType);
        long listsCount = taskListUserRepository.countByUserId(user.getId());
        boolean canCreateList = limits.getMaxLists() == -1 || listsCount < limits.getMaxLists();

        return SubscriptionStatusResponse.builder()
                .subscriptionType(effectiveType)
                .subscriptionExpiresAt(user.getSubscriptionExpiresAt())
                .betaTester(user.isBetaTester())
                .limits(SubscriptionStatusResponse.Limits.builder()
                        .maxLists(limits.getMaxLists())
                        .maxTasksPerList(limits.getMaxTasksPerList())
                        .maxMembersPerList(limits.getMaxMembersPerList())
                        .privateTasksAllowed(limits.isPrivateTasksAllowed())
                        .build())
                .usage(SubscriptionStatusResponse.Usage.builder()
                        .listsCount(listsCount)
                        .canCreateList(canCreateList)
                        .build())
                .build();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
    }

    private AppProperties.SubscriptionLimits getLimitsForUser(User user) {
        return getLimitsForType(getEffectiveSubscriptionType(user));
    }
}
