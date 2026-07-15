package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.InviteInfoResponse;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.dto.list.ReorderItem;
import ru.mngerasimenko.todolist.exception.ListNotFoundException;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TaskListMapper;
import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.InviteToken;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskListServiceImpl implements TaskListService {

    private final TaskListRepository taskListRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final UserRepository userRepository;
    private final TodoRepository todoRepository;
    private final TaskListMapper taskListMapper;
    private final TodoMapper todoMapper;
    private final SubscriptionService subscriptionService;
    private final InviteTokenRepository inviteTokenRepository;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final PushNotificationService pushNotificationService;
    private final CacheManager cacheManager;

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    @CacheEvict(value = RedisCacheConfig.TASK_LISTS, key = "#creatorUserId")
    public ListResponse createList(String name, Long creatorUserId) {
        subscriptionService.assertCanCreateList(creatorUserId);

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + creatorUserId));

        TaskList taskList = new TaskList(name, creator);

        TaskList savedTaskList;
        try {
            savedTaskList = taskListRepository.saveAndFlush(taskList);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("У вас уже есть список с названием '" + name + "'");
        }

        TaskListUser taskListUser = new TaskListUser(savedTaskList, creator, TaskListRole.ADMIN);
        taskListUserRepository.save(taskListUser);

        log.info("Создан список: id={}, name='{}', creatorId={}", savedTaskList.getId(), name, creatorUserId);
        return taskListMapper.toResponse(savedTaskList, TaskListRole.ADMIN);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisCacheConfig.TASK_LISTS, key = "#userId",
            condition = RedisCacheConfig.CACHE_CONDITION)
    public List<ListResponse> getListsByUserId(Long userId) {
        List<TaskListUser> taskListUsers = taskListUserRepository.findByUserId(userId);
        return taskListUsers.stream()
                .map(tlu -> taskListMapper.toResponse(tlu.getTaskList(), tlu.getRole(), tlu.getPosition(), tlu.getColor()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListMemberResponse> getMembers(Long listId, Long requestingUserId) {
        if (!taskListRepository.existsById(listId)) {
            throw new ListNotFoundException("List not found. It may have been deleted");
        }
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        List<TaskListUser> members = taskListUserRepository.findByIdListId(listId);
        return members.stream()
                .map(taskListMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByList(Long listId, Long requestingUserId) {
        if (!taskListRepository.existsById(listId)) {
            throw new ListNotFoundException("List not found. It may have been deleted");
        }
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        return todoRepository.findByListIdVisibleToUser(listId, requestingUserId).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public String leaveList(Long listId, Long userId) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        // Для evict'а кэша task-lists: минимум сам уходящий пользователь.
        // Если уходит ADMIN и есть другие участники — второй ADMIN тоже попадает
        // под инвалидацию (меняется role; при конфликте имён меняется name списка).
        List<Long> affectedUserIds = new ArrayList<>();
        affectedUserIds.add(userId);

        String message;

        if (membership.getRole() == TaskListRole.ADMIN) {
            List<TaskListUser> allMembers = taskListUserRepository.findByIdListId(listId);

            if (allMembers.size() == 1) {
                // ADMIN единственный — удаляем список целиком
                inviteTokenRepository.deleteByListId(listId);
                todoRepository.deleteByListId(listId);
                taskListUserRepository.deleteByListId(listId);
                taskListRepository.deleteByListId(listId);
                log.info("Список удалён при выходе последнего участника: listId={}, userId={}", listId, userId);
                message = "Список удалён, так как вы были единственным участником";
            } else {
                // ADMIN с другими участниками — передаём права первому
                TaskList taskList = membership.getTaskList();
                allMembers.stream()
                        .filter(m -> !m.getUser().getId().equals(userId))
                        .findFirst()
                        .ifPresent(m -> {
                            affectedUserIds.add(m.getUser().getId());
                            m.setRole(TaskListRole.ADMIN);
                            taskListUserRepository.saveAndFlush(m);
                            // Если уходящий — создатель списка, передаём creator_id новому ADMIN
                            if (taskList.getCreatorId() != null && taskList.getCreatorId().equals(userId)) {
                                String previousCreatorName = membership.getUser().getName();
                                taskList.setCreator(m.getUser());
                                try {
                                    taskListRepository.saveAndFlush(taskList);
                                } catch (DataIntegrityViolationException e) {
                                    // У нового ADMIN уже есть список с таким именем —
                                    // переименовываем: "ремонт" → "ремонт (Иван)"
                                    taskList.setName(taskList.getName() + " (" + previousCreatorName + ")");
                                    taskListRepository.saveAndFlush(taskList);
                                    log.info("Список переименован при передаче: '{}', listId={}",
                                            taskList.getName(), taskList.getId());
                                }
                            }
                        });
                todoRepository.deletePrivateTodosByListIdAndUserId(listId, userId);
                taskListUserRepository.deleteByListIdAndUserId(listId, userId);
                log.info("Администратор вышел из списка, права переданы: listId={}, userId={}", listId, userId);
                message = "Вы покинули список. Права администратора переданы другому участнику";
            }
        } else {
            // Обычный участник
            todoRepository.deletePrivateTodosByListIdAndUserId(listId, userId);
            taskListUserRepository.deleteByListIdAndUserId(listId, userId);
            log.info("Пользователь вышел из списка: listId={}, userId={}", listId, userId);
            message = "Вы покинули список";
        }

        evictTaskListsCache(affectedUserIds);
        return message;
    }

    @Override
    @Transactional
    public void deleteList(Long listId, Long userId) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (membership.getRole() != TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Только администратор может удалить список");
        }

        // До удаления собираем userIds всех участников — у каждого нужно почистить
        // закешированный список, иначе они будут видеть призрак удалённого списка до TTL.
        List<Long> affectedUserIds = taskListUserRepository.findByIdListId(listId).stream()
                .map(m -> m.getUser().getId())
                .toList();

        inviteTokenRepository.deleteByListId(listId);
        todoRepository.deleteByListId(listId);
        taskListUserRepository.deleteByListId(listId);
        taskListRepository.deleteByListId(listId);

        log.info("Список удалён: listId={}, userId={}", listId, userId);
        evictTaskListsCache(affectedUserIds);
    }

    @Override
    @Transactional
    public void removeMember(Long listId, Long requesterId, Long targetUserId) {
        TaskListUser requester = taskListUserRepository.findByIdListIdAndIdUserId(listId, requesterId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (requester.getRole() != TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Только администратор может удалять участников");
        }

        // Проверяем self до загрузки цели: у себя роль ADMIN, для выхода есть leaveList.
        if (targetUserId.equals(requesterId)) {
            throw new IllegalArgumentException("Нельзя удалить самого себя — используйте выход из списка");
        }

        TaskListUser target = taskListUserRepository.findByIdListIdAndIdUserId(listId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Участник не найден в списке"));

        if (target.getRole() == TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Нельзя удалить администратора списка");
        }

        // Явная защита создателя: в норме создатель всегда ADMIN (отсекается выше), но
        // enforce'им требование напрямую по creator_id — на случай рассинхрона роли и creator_id.
        Long creatorId = target.getTaskList().getCreatorId();
        if (creatorId != null && creatorId.equals(targetUserId)) {
            throw new IllegalArgumentException("Нельзя удалить создателя списка");
        }

        // Приватные задачи удаляемого чистим, общие — остаются в списке (как при выходе).
        todoRepository.deletePrivateTodosByListIdAndUserId(listId, targetUserId);
        // Удаляем связь через delete(target), а не bulk-запросом: так уважается @Version.
        // (deletePrivateTodos выше — @Modifying(clearAutomatically=true) — очищает PC, поэтому
        // target detached и delete идёт через merge, который тоже сверяет версию.) При гонке
        // с leaveList (промоут цели в ADMIN) операция упрётся в устаревшую версию
        // → ObjectOptimisticLockingFailureException → 409, а не осиротевший список без админа.
        taskListUserRepository.delete(target);

        // Инвалидируем кеш task-lists только удаляемого — у него список должен исчезнуть.
        // Набор списков админа не меняется, его кеш не трогаем.
        evictTaskListsCache(List.of(targetUserId));

        log.info("Участник удалён из списка: listId={}, adminId={}, removedUserId={}",
                listId, requesterId, targetUserId);
    }

    @Override
    @Transactional
    public ListResponse updateList(Long listId, Long requesterId, String name) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, requesterId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (membership.getRole() != TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Только администратор может изменять список");
        }

        // name null — no-op: возвращаем текущее состояние без записи/инвалидации кеша
        if (name == null) {
            return taskListMapper.toResponse(membership.getTaskList(), membership.getRole(),
                    membership.getPosition(), membership.getColor());
        }

        TaskList list = membership.getTaskList();
        list.setName(name);
        TaskList saved = taskListRepository.saveAndFlush(list);

        // Имя — общее поле списка, кеш task-lists неактуален для всех участников.
        List<Long> affectedUserIds = taskListUserRepository.findByIdListId(listId).stream()
                .map(m -> m.getUser().getId())
                .toList();
        evictTaskListsCache(affectedUserIds);

        log.info("Список переименован: listId={}, userId={}", listId, requesterId);
        return taskListMapper.toResponse(saved, TaskListRole.ADMIN,
                membership.getPosition(), membership.getColor());
    }

    @Override
    @Transactional
    public ListResponse updatePersonalization(Long listId, Long userId, String color) {
        // Любой участник может задать свой персональный цвет (не только ADMIN).
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        membership.setColor(color);
        taskListUserRepository.saveAndFlush(membership);

        // Персональное изменение — инвалидируем кеш только этого юзера.
        evictTaskListsCache(List.of(userId));

        log.info("Персонализация списка обновлена: listId={}, userId={}, color={}",
                listId, userId, color != null);
        return taskListMapper.toResponse(membership.getTaskList(), membership.getRole(),
                membership.getPosition(), membership.getColor());
    }

    @Override
    @Transactional
    public void reorderLists(Long userId, List<ReorderItem> items) {
        List<Long> listIds = items.stream().map(ReorderItem::id).toList();

        // Fail-fast: дубликаты id в запросе клиента — это malformed input.
        // Без этой проверки Collectors.toMap ниже бросит IllegalStateException → HTTP 500;
        // с проверкой получаем IllegalArgumentException → HTTP 400 через GlobalExceptionHandler.
        if (listIds.stream().distinct().count() != listIds.size()) {
            throw new IllegalArgumentException("Duplicate list ids in reorder request");
        }

        // Симметричная проверка: дубликаты position'ов — UX-баг клиента, после reorder
        // два списка имели бы одинаковый position и порядок не стабилизировался бы.
        List<Integer> positions = items.stream().map(ReorderItem::position).toList();
        if (positions.stream().distinct().count() != positions.size()) {
            throw new IllegalArgumentException("Duplicate positions in reorder request");
        }

        List<TaskListUser> links = taskListUserRepository.findByIdUserIdAndIdListIdIn(userId, listIds);

        // Все id из запроса должны принадлежать юзеру — иначе reorder отклоняется целиком
        if (links.size() != items.size()) {
            throw new IllegalArgumentException("Some lists do not belong to user " + userId);
        }

        Map<Long, Integer> newPositions = items.stream()
                .collect(Collectors.toMap(ReorderItem::id, ReorderItem::position));

        links.forEach(link -> link.setPosition(newPositions.get(link.getId().getListId())));

        taskListUserRepository.saveAllAndFlush(links);

        // Reorder затрагивает только текущего юзера — evict его кеш task-lists
        evictTaskListsCache(List.of(userId));

        log.info("Списки переупорядочены: userId={}, count={}", userId, items.size());
    }

    @Override
    @Transactional
    public void reorderTodos(Long listId, Long requesterId, List<ReorderItem> items) {
        // Fail-fast: дубликаты id — malformed input. Без этой проверки Collectors.toMap
        // ниже бросит IllegalStateException → HTTP 500.
        List<Long> todoIds = items.stream().map(ReorderItem::id).toList();
        if (todoIds.stream().distinct().count() != todoIds.size()) {
            throw new IllegalArgumentException("Duplicate todo ids in reorder request");
        }

        // Симметричная проверка: дубликаты position'ов — UX-баг клиента.
        List<Integer> positions = items.stream().map(ReorderItem::position).toList();
        if (positions.stream().distinct().count() != positions.size()) {
            throw new IllegalArgumentException("Duplicate positions in reorder request");
        }

        // Проверка участия — любой участник списка может реордерить (не только ADMIN)
        taskListUserRepository.findByIdListIdAndIdUserId(listId, requesterId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        List<Todo> todos = todoRepository.findByIdInAndListId(todoIds, listId);

        if (todos.size() != items.size()) {
            throw new IllegalArgumentException("Some todos do not belong to list " + listId);
        }

        Map<Long, Integer> newPositions = items.stream()
                .collect(Collectors.toMap(ReorderItem::id, ReorderItem::position));

        todos.forEach(t -> t.setPosition(newPositions.get(t.getId())));

        todoRepository.saveAllAndFlush(todos);
        // Кеш todos в проекте не используется (TodoServiceImpl без @Cacheable) — evict не нужен.

        log.info("Задачи переупорядочены: listId={}, userId={}, count={}", listId, requesterId, items.size());
    }

    @Override
    @Transactional
    public InviteResponse createInvite(Long listId, Long userId, String recipientEmail) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (membership.getRole() != TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Только администратор может создавать приглашения");
        }

        TaskList taskList = membership.getTaskList();
        User inviter = membership.getUser();

        // Генерация токена: сырой UUID → SHA-256 хеш в БД
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(emailProperties.getInviteTokenTtlHours());

        InviteToken inviteToken = new InviteToken(tokenHash, taskList, inviter, expiresAt);
        inviteTokenRepository.save(inviteToken);

        String inviteLink = emailProperties.getBaseUrl() + "/invite/" + rawToken;

        // Отправка email, если указан получатель.
        // Локаль берём от inviter — приглашаемый может ещё не иметь аккаунта,
        // и эвристика "у inviter и invitee одинаковый язык" обычно срабатывает.
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            emailService.sendInviteEmail(recipientEmail, inviteLink, taskList.getName(),
                    inviter.getName(), inviter.getPreferredEmailLocale());
        }

        log.info("Создано приглашение: listId={}, inviterId={}, email={}", listId, userId, maskEmail(recipientEmail));
        return InviteResponse.builder()
                .inviteLink(inviteLink)
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public InviteInfoResponse getInviteInfo(String token) {
        String tokenHash = TokenUtils.sha256(token);
        InviteToken inviteToken = inviteTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenExpiredException("Приглашение не найдено или недействительно"));

        if (inviteToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new TokenExpiredException("Срок действия приглашения истёк");
        }

        // Маскируем имя приглашающего для неавторизованных пользователей (Vuln #8)
        String fullName = inviteToken.getInviter().getName();
        String maskedName = fullName.substring(0, 1) + "***";

        return InviteInfoResponse.builder()
                .listName(inviteToken.getTaskList().getName())
                .inviterName(maskedName)
                .expiresAt(inviteToken.getExpiresAt())
                .build();
    }

    @Override
    @Transactional
    @CacheEvict(value = RedisCacheConfig.TASK_LISTS, key = "#userId")
    public ListResponse acceptInvite(String token, Long userId) {
        String tokenHash = TokenUtils.sha256(token);
        InviteToken inviteToken = inviteTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenExpiredException("Приглашение не найдено или недействительно"));

        if (inviteToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new TokenExpiredException("Срок действия приглашения истёк");
        }

        TaskList taskList = inviteToken.getTaskList();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        // Проверка: уже в списке?
        Optional<TaskListUser> existing =
                taskListUserRepository.findByIdListIdAndIdUserId(taskList.getId(), userId);
        if (existing.isPresent()) {
            log.info("Пользователь уже в списке (invite): listId={}, userId={}", taskList.getId(), userId);
            return taskListMapper.toResponse(taskList, existing.get().getRole());
        }

        subscriptionService.assertCanJoinList(taskList.getId(), userId);

        TaskListRole role = taskListUserRepository.existsByIdListIdAndRole(taskList.getId(), TaskListRole.ADMIN)
                ? TaskListRole.USER
                : TaskListRole.ADMIN;

        TaskListUser taskListUser = new TaskListUser(taskList, user, role);
        taskListUserRepository.save(taskListUser);

        log.info("Пользователь вступил в список по приглашению: listId={}, userId={}, role={}", taskList.getId(), userId, role);

        // Push-уведомление всем участникам о новом участнике
        pushNotificationService.notifyNewMember(
                taskList.getId(), userId, user.getName(), taskList.getName());

        return taskListMapper.toResponse(taskList, role);
    }

    /**
     * Удаление записей из кэша {@code task-lists} для нескольких пользователей сразу.
     * Используется в leaveList/deleteList, когда мутация затрагивает кэш всех
     * участников списка (передача ADMIN-прав, удаление списка, rename при конфликте имён).
     *
     * Evict регистрируется как afterCommit-synchronization, чтобы при rollback
     * транзакции не чистить кэш зря (и не отдать пользователю старое значение как «свежее»).
     */
    private void evictTaskListsCache(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache(RedisCacheConfig.TASK_LISTS);
            if (cache == null) return;
            for (Long uid : userIds) {
                if (uid != null) cache.evict(uid);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else {
            evict.run();
        }
    }
}
