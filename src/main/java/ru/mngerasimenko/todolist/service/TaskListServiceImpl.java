package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.InviteInfoResponse;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.exception.ListNotFoundException;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TaskListMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.InviteToken;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
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
    public List<ListResponse> getListsByUserId(Long userId) {
        List<TaskListUser> taskListUsers = taskListUserRepository.findByUserId(userId);
        return taskListUsers.stream()
                .map(tlu -> taskListMapper.toResponse(tlu.getTaskList(), tlu.getRole()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListMemberResponse> getMembers(Long listId, Long requestingUserId) {
        if (!taskListRepository.existsById(listId)) {
            throw new ListNotFoundException("Список не найден. Возможно, он был удалён");
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
            throw new ListNotFoundException("Список не найден. Возможно, он был удалён");
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

        inviteTokenRepository.deleteByListId(listId);
        todoRepository.deleteByListId(listId);
        taskListUserRepository.deleteByListId(listId);
        taskListRepository.deleteByListId(listId);

        log.info("Список удалён: listId={}, userId={}", listId, userId);
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

        // Отправка email, если указан получатель
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            emailService.sendInviteEmail(recipientEmail, inviteLink, taskList.getName(), inviter.getName());
        }

        log.info("Создано приглашение: listId={}, inviterId={}, email={}", listId, userId, recipientEmail);
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

        return InviteInfoResponse.builder()
                .listName(inviteToken.getTaskList().getName())
                .inviterName(inviteToken.getInviter().getName())
                .expiresAt(inviteToken.getExpiresAt())
                .build();
    }

    @Override
    @Transactional
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
        return taskListMapper.toResponse(taskList, role);
    }
}
