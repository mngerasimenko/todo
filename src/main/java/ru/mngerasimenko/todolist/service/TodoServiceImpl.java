package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.exception.ListNotFoundException;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import org.springframework.security.access.AccessDeniedException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final TaskListRepository taskListRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final PushNotificationService pushNotificationService;
    private final TodoMapper todoMapper;
    private final SubscriptionService subscriptionService;
    private final SuggestionService suggestionService;

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Moscow");

    @Override
    @Transactional
    public TodoDto createTodo(TodoDto todoDto) {
        User user = userRepository.findById(todoDto.getUserId())
                .orElseThrow(() -> new UserNotFoundException(
                        "User not found with id: " + todoDto.getUserId()));

        TaskList taskList = taskListRepository.findById(todoDto.getListId())
                .orElseThrow(() -> new ListNotFoundException(
                        "List not found. It may have been deleted"));

        if (!taskListUserRepository.existsByIdListIdAndIdUserId(todoDto.getListId(), todoDto.getUserId())) {
            throw new IllegalArgumentException("Пользователь не является участником данного списка");
        }

        subscriptionService.assertCanCreateTodo(todoDto.getListId(), todoDto.getUserId());
        if (todoDto.isPrivate()) {
            subscriptionService.assertCanCreatePrivateTodo(todoDto.getUserId());
        }

        todoDto.setDone(false);
        todoDto.setUserId(user.getId());
        todoDto.setCreatedAt(LocalDateTime.now());
        Todo todo = todoMapper.toEntity(todoDto);
        todo.setUser(user);
        todo.setTaskList(taskList);
        applyDueRules(todoDto, todo);

        Todo savedTodo = todoRepository.save(todo);
        log.info("Создана задача: id={}, name='{}', userId={}, listId={}, private={}",
                savedTodo.getId(), savedTodo.getName(), user.getId(), taskList.getId(), savedTodo.getIsPrivate());

        // Push-уведомление участникам списка (не для приватных задач)
        if (!savedTodo.getIsPrivate()) {
            pushNotificationService.notifyNewTodo(
                    taskList.getId(), user.getId(), user.getName(), savedTodo.getName());
        }

        // Пополнение глобального словаря подсказок (Server R-6). Делаем строго в afterCommit,
        // чтобы при rollback'е транзакции createTodo словарь не получил «фантомную» запись.
        // REQUIRES_NEW на стороне SuggestionService гарантирует отдельную транзакцию для UPSERT.
        final String trackText = savedTodo.getName();
        final boolean trackPrivate = Boolean.TRUE.equals(savedTodo.getIsPrivate());
        // userId нужен для distinct-учёта (k-анонимность): строка всплывает только при N разных
        // авторах, поэтому track считает именно РАЗНЫХ пользователей (gate-чейн /ideas 2026-06-23).
        final Long trackUserId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        suggestionService.track(trackText, trackPrivate, trackUserId);
                    } catch (RuntimeException ex) {
                        log.warn("[suggestions] afterCommit track failed: {}", ex.toString());
                    }
                }
            });
        } else {
            // Без активной TX (теоретически — если кто-то позовёт createTodo вне @Transactional):
            // tracking сразу же, в обычном потоке.
            try {
                suggestionService.track(trackText, trackPrivate, trackUserId);
            } catch (RuntimeException ex) {
                log.warn("[suggestions] inline track failed: {}", ex.toString());
            }
        }

        return todoMapper.toDto(savedTodo);
    }

    @Override
    @Transactional
    public TodoDto updateTodo(Long id, TodoDto todoDto, Long requestingUserId) {
        Todo existingTodo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(existingTodo, requestingUserId, false);

        if (todoDto.getUserId() != null && !todoDto.getUserId().equals(existingTodo.getUserId())) {
            User newUser = userRepository.findById(todoDto.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(
                            "User not found with id: " + todoDto.getUserId()));
            existingTodo.setUser(newUser);
        }

        boolean wasDone = Boolean.TRUE.equals(existingTodo.isDone());
        boolean nowDone = todoDto.isDone();

        log.debug("updateTodo: входной done={}, существующий done={}", nowDone, wasDone);

        // Снимок полей срока ДО маппинга: updateEntityFromDto безусловно копирует их
        // из dto, и после него entity уже совпадает с dto — applyDueRules не увидел бы
        // разницы. Восстанавливаем "было" сразу после маппинга, чтобы правило "смена
        // срока обнуляет reminderSentAt" реально ловило перенос, а не сравнивало dto само с собой.
        LocalDate dueDateBeforeMapping = existingTodo.getDueDate();
        LocalTime dueTimeBeforeMapping = existingTodo.getDueTime();
        Integer remindBeforeMinutesBeforeMapping = existingTodo.getRemindBeforeMinutes();

        todoMapper.updateEntityFromDto(todoDto, existingTodo);

        existingTodo.setDueDate(dueDateBeforeMapping);
        existingTodo.setDueTime(dueTimeBeforeMapping);
        existingTodo.setRemindBeforeMinutes(remindBeforeMinutesBeforeMapping);
        applyDueRules(todoDto, existingTodo);

        // Логика completedAt и completorUser
        if (!wasDone && nowDone) {
            // Задача выполнена: проставляем время выполнения и исполнителя
            existingTodo.setCompletedAt(LocalDateTime.now());
            if (todoDto.getCompletorUserId() != null) {
                User completor = userRepository.findById(todoDto.getCompletorUserId())
                        .orElse(null);
                existingTodo.setCompletorUser(completor);
            }
        } else if (wasDone && !nowDone) {
            // Задача снята с выполнения: очищаем completedAt и completorUser
            existingTodo.setCompletedAt(null);
            existingTodo.setCompletorUser(null);
        }

        Todo updatedTodo = todoRepository.save(existingTodo);
        log.info("Обновлена задача: id={}, name='{}', done={}, completedAt={}",
                updatedTodo.getId(), updatedTodo.getName(), updatedTodo.isDone(), updatedTodo.getCompletedAt());
        return todoMapper.toDto(updatedTodo);
    }

    @Override
    @Transactional(readOnly = true)
    public TodoDto getTodoById(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(todo, requestingUserId, true);
        return todoMapper.toDto(todo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getAllTodos(Long requestingUserId) {
        List<Long> listIds = taskListUserRepository.findListIdsByUserId(requestingUserId);
        if (listIds.isEmpty()) {
            return List.of();
        }
        return todoRepository.findByListIdsVisibleToUser(listIds, requestingUserId).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getFilteredTodosByUserId(Long userId, String filter) {
        return todoRepository.findAllByUserIdAndNameContainingIgnoreCase(userId, filter).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByUserId(Long userId, Long requestingUserId) {
        List<Todo> todos = todoRepository.findByUserId(userId);
        log.debug("Загрузка задач для userId={}, requestingUserId={}, найдено: {}",
                userId, requestingUserId, todos.size());
        // Фильтрация: приватные задачи видны только их создателю
        if (!userId.equals(requestingUserId)) {
            todos = todos.stream()
                    .filter(todo -> !Boolean.TRUE.equals(todo.getIsPrivate()))
                    .toList();
        }
        return todos.stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getActiveTodosByUserId(Long userId) {
        return todoRepository.findByUserIdAndDone(userId, false).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getCompletedTodosByUserId(Long userId) {
        return todoRepository.findByUserIdAndDone(userId, true).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void deleteTodo(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(todo, requestingUserId, false);
        todoRepository.deleteById(id);
        log.info("Удалена задача: id={}, userId={}", id, requestingUserId);
    }

    @Override
    @Transactional
    public void deleteTodosByUserId(Long userId) {
        todoRepository.deleteByUserId(userId);
    }

    @Override
    @Transactional
    public TodoDto markAsDone(Long id, Long completorUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(todo, completorUserId, true);
        todo.setDone(true);
        todo.setCompletedAt(LocalDateTime.now());
        User completor = null;
        if (completorUserId != null) {
            completor = userRepository.findById(completorUserId).orElse(null);
            todo.setCompletorUser(completor);
        }
        Todo updatedTodo = todoRepository.save(todo);

        // Push-уведомление всем участникам списка (кроме того, кто выполнил)
        if (completor != null) {
            pushNotificationService.notifyTodoCompleted(
                    completorUserId, todo.getTaskList().getId(), completor.getName(), todo.getName());
        }

        return todoMapper.toDto(updatedTodo);
    }

    @Override
    @Transactional
    public TodoDto markAsUndone(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(todo, requestingUserId, true);
        todo.setDone(false);
        todo.setCompletedAt(null);
        todo.setCompletorUser(null);
        Todo updatedTodo = todoRepository.save(todo);
        return todoMapper.toDto(updatedTodo);
    }

    /**
     * Единая точка правил срока. Вызывается и при создании, и при обновлении:
     * иначе правило, добавленное в одну ветку, тихо не сработает в другой.
     */
    private void applyDueRules(TodoDto dto, Todo entity) {
        if (dto.getDueDate() == null) {
            // Снятие срока: чистим всё сопутствующее, иначе повторная установка
            // унаследует настройки, которых пользователь уже не видит на экране.
            entity.setDueDate(null);
            entity.setDueTimezone(null);
            entity.setDueTime(LocalTime.of(9, 0));
            entity.setRemindBeforeMinutes(0);
            entity.setReminderScope(ReminderScope.SELF);
            entity.setReminderSentAt(null);
            return;
        }

        boolean momentChanged = !Objects.equals(entity.getDueDate(), dto.getDueDate())
                || !Objects.equals(entity.getDueTime(), dto.getDueTime())
                || !Objects.equals(entity.getRemindBeforeMinutes(), dto.getRemindBeforeMinutes());

        entity.setDueDate(dto.getDueDate());
        entity.setDueTime(dto.getDueTime() != null ? dto.getDueTime() : LocalTime.of(9, 0));
        entity.setRemindBeforeMinutes(dto.getRemindBeforeMinutes() != null ? dto.getRemindBeforeMinutes() : 0);

        if (dto.getDueTimezone() == null || dto.getDueTimezone().isBlank()) {
            log.warn("Задача id={} получила срок без часового пояса — клиент старой версии, подставлен {}",
                    entity.getId(), FALLBACK_ZONE);
            entity.setDueTimezone(FALLBACK_ZONE.getId());
        } else {
            // Невалидный IANA-идентификатор не должен доходить до колонки: findDueForReminder —
            // один native-запрос по всем задачам, и AT TIME ZONE упадёт на первой же "плохой"
            // строке, оборвав рассылку напоминаний сразу для всех пользователей.
            try {
                ZoneId.of(dto.getDueTimezone());
                entity.setDueTimezone(dto.getDueTimezone());
            } catch (DateTimeException e) {
                log.warn("Задача id={} получила невалидный часовой пояс '{}', подставлен {}",
                        entity.getId(), dto.getDueTimezone(), FALLBACK_ZONE);
                entity.setDueTimezone(FALLBACK_ZONE.getId());
            }
        }

        // Приватную задачу видит только автор — рассылать её участникам списка нельзя
        // ни при каком значении, пришедшем от клиента. API публичный, доверять ему нельзя.
        ReminderScope requested = dto.getReminderScope() != null ? dto.getReminderScope() : ReminderScope.SELF;
        entity.setReminderScope(entity.getIsPrivate() ? ReminderScope.SELF : requested);

        if (momentChanged) {
            entity.setReminderSentAt(null);
        }
    }

    /**
     * Проверяет права доступа к задаче.
     *
     * Правила доступа:
     * 1. Только участник списка может работать с задачами
     * 2. Владелец задачи может делать всё
     * 3. Приватные задачи доступны только их создателю
     * 4. Отметка выполнения — любой участник списка (коллаборация)
     * 5. Редактирование/удаление — только владелец или ADMIN списка
     *
     * @param todo задача
     * @param userId ID текущего пользователя
     * @param isMarkAction true для отметки/снятия выполнения, false для редактирования/удаления
     * @throws AccessDeniedException если доступ запрещён
     */
    private void assertCanModifyTodo(Todo todo, Long userId, boolean isMarkAction) {
        Long listId = todo.getTaskList().getId();
        Long todoOwnerId = todo.getUser().getId();

        // 1. Проверка на участника списка
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Доступ запрещён: пользователь не является участником списка задачи"));

        // 2. Владелец задачи — доступ разрешён
        if (todoOwnerId.equals(userId)) {
            return;
        }

        // 3. Приватные задачи — только владелец
        if (todo.getIsPrivate()) {
            log.warn("Попытка доступа к чужой приватной задаче id={} пользователем id={}", todo.getId(), userId);
            throw new AccessDeniedException(
                    "Приватные задачи доступны только их создателю");
        }

        // 4. Отметка выполнения — любой участник списка
        if (isMarkAction) {
            return;
        }

        // 5. Редактирование/удаление — только ADMIN
        if (membership.getRole() == TaskListRole.ADMIN) {
            return;
        }

        // 6. Обычный USER не может редактировать/удалять чужие задачи
        log.warn("Пользователь id={} попытался изменить чужую задачу id={}", userId, todo.getId());
        throw new AccessDeniedException(
                "Только создатель задачи или администратор списка могут изменить эту задачу");
    }
}
