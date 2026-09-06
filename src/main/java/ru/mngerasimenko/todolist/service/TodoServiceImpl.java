package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.mngerasimenko.todolist.dto.DueTodosResponse;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.exception.ListNotFoundException;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final TaskListRepository taskListRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final PushNotificationService pushNotificationService;
    private final EmailService emailService;
    private final UserService userService;
    private final TodoMapper todoMapper;
    private final SubscriptionService subscriptionService;
    private final SuggestionService suggestionService;
    private final FeatureFlagStore flagStore;

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Moscow");

    /** Нижняя граница «протухания» напоминания — см. TodoRepository.findDueForReminder. */
    private static final Duration STALE_AFTER = Duration.ofDays(1);

    /** Горизонт группы «Дальше». Без границы экран превращается во второй список всех задач. */
    private static final int UPCOMING_DAYS = 7;

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

        // Ключ идемпотентности: клиент — очередь at-least-once и при потерянном ответе
        // повторяет POST. Повтор возвращает уже созданную задачу вместо второй строки.
        // Проверка идёт ДО лимита подписки: у пользователя на лимите ретрай иначе получил бы
        // 402, и клиент откатил бы локальную задачу, хотя на сервере она уже есть.
        // Гонку двух одновременных ретраев ловит не этот SELECT (при READ COMMITTED второй
        // ещё не видит незакоммиченную строку первого), а частичный уникальный индекс
        // uq_todo_user_client_request_id из миграции 031: проигравший получает 409, клиент
        // на 409 повторяет, и повтор уже находится здесь. Дубль не создаётся ни в одном случае.
        String requestKey = clientRequestKey(todoDto);
        if (requestKey != null) {
            Optional<Todo> alreadyCreated = todoRepository
                    .findFirstByUserIdAndClientRequestIdOrderByIdAsc(todoDto.getUserId(), requestKey);
            if (alreadyCreated.isPresent()) {
                Todo existing = alreadyCreated.get();
                log.info("Повторное создание распознано по ключу: id={}, userId={}, listId={}, key={}",
                        existing.getId(), todoDto.getUserId(), todoDto.getListId(), requestKey);
                warnIfRetryPayloadDiverged(existing, todoDto, requestKey);
                return todoMapper.toDto(existing);
            }
        }

        subscriptionService.assertCanCreateTodo(todoDto.getListId(), todoDto.getUserId());
        if (todoDto.isPrivate()) {
            subscriptionService.assertCanCreatePrivateTodo(todoDto.getUserId());
        }

        todoDto.setDone(false);
        todoDto.setUserId(user.getId());
        // При выключенном флаге ключ не сохраняем: иначе уникальный индекс продолжил бы
        // отбивать ретраи 409-ми, хотя механизм выключен. Флаг обязан возвращать «как было».
        todoDto.setClientRequestId(requestKey);
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

    /**
     * Ключ идемпотентности запроса — или {@code null}, если механизм не применяется:
     * клиент ключ не прислал (сборки до этой правки, веб-клиент) либо он выключен флагом.
     * Пустая строка равносильна отсутствию: пускать её в уникальный индекс нельзя,
     * иначе два разных намерения с пустым ключом схлопнулись бы в одну задачу.
     */
    private String clientRequestKey(TodoDto todoDto) {
        if (!flagStore.isEnabled(FeatureFlag.TODO_CREATE_DEDUPE)) {
            return null;
        }
        String key = todoDto.getClientRequestId();
        if (key == null || key.isBlank()) {
            return null;
        }
        // Нормализуем края: иначе " K" и "K" стали бы двумя разными ключами одного намерения.
        return key.trim();
    }

    /**
     * Единственная наблюдаемость за клиентом, который переиспользует ключ не по назначению.
     * По контракту «один ключ — одно намерение» повтор обязан нести тот же payload; если он
     * разошёлся (например, клиент правит уже поставленную в очередь операцию создания),
     * пользователь молча получит назад старое состояние задачи. Само поведение не меняем —
     * ключ авторитетнее payload'а, — но такое расхождение обязано быть видно в логах.
     * Имя задачи не логируем: колонка зашифрована at rest, plaintext в логи не выносим.
     */
    private void warnIfRetryPayloadDiverged(Todo existing, TodoDto todoDto, String requestKey) {
        boolean listDiverged = !Objects.equals(existing.getListId(), todoDto.getListId());
        boolean nameDiverged = !Objects.equals(existing.getName(), todoDto.getName());
        boolean privacyDiverged = existing.getIsPrivate() != todoDto.isPrivate();
        if (listDiverged || nameDiverged || privacyDiverged) {
            log.warn("Повтор по ключу {} разошёлся с созданной задачей id={}: list={}, name={}, private={}"
                            + " — возвращаем созданную, изменения повтора игнорируются",
                    requestKey, existing.getId(), listDiverged, nameDiverged, privacyDiverged);
        }
    }

    @Override
    @Transactional
    public TodoDto updateTodo(Long id, TodoDto todoDto, Long requestingUserId) {
        Todo existingTodo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertCanModifyTodo(existingTodo, requestingUserId, false);

        // Автор задачи неизменен. Раньше user_id из тела переназначал авторство на любого
        // пользователя системы — членство нового «автора» в списке не проверялось, и задачу
        // можно было «подбросить» постороннему, после чего она всплывала в его выборках.
        // Легитимного сценария у поля нет: оба клиента шлют сюда автора задачи, а не редактора,
        // поэтому значение просто игнорируется — расхождение уходит в лог как сигнал подмены.
        if (todoDto.getUserId() != null && !todoDto.getUserId().equals(existingTodo.getUserId())) {
            log.warn("Попытка сменить автора задачи id={}: user_id из тела={}, автор остаётся={}",
                    id, todoDto.getUserId(), existingTodo.getUserId());
        }

        boolean wasDone = Boolean.TRUE.equals(existingTodo.isDone());
        boolean nowDone = todoDto.isDone();

        log.debug("updateTodo: входной done={}, существующий done={}", nowDone, wasDone);

        // Снимок ВСЕХ due-полей ДО маппинга: updateEntityFromDto безусловно копирует их
        // из dto, и после него entity уже совпадает с dto — applyDueRules не увидел бы
        // разницы. Восстанавливаем "было" сразу после маппинга и только потом решаем,
        // применять ли due-правила вообще (см. dueFieldsProvided ниже) — CRITICAL из
        // финального ревью ветки: оба выпущенных клиента (веб-форма, Android TodoRequest)
        // шлют обновление вообще без due-ключей, и dto.getDueDate()==null в этом случае
        // неотличим от явного "снять срок", если due-поля entity не восстановлены целиком.
        LocalDate dueDateBeforeMapping = existingTodo.getDueDate();
        LocalTime dueTimeBeforeMapping = existingTodo.getDueTime();
        String dueTimezoneBeforeMapping = existingTodo.getDueTimezone();
        Integer remindBeforeMinutesBeforeMapping = existingTodo.getRemindBeforeMinutes();
        ReminderScope reminderScopeBeforeMapping = existingTodo.getReminderScope();

        todoMapper.updateEntityFromDto(todoDto, existingTodo);

        existingTodo.setDueDate(dueDateBeforeMapping);
        existingTodo.setDueTime(dueTimeBeforeMapping);
        existingTodo.setDueTimezone(dueTimezoneBeforeMapping);
        existingTodo.setRemindBeforeMinutes(remindBeforeMinutesBeforeMapping);
        existingTodo.setReminderScope(reminderScopeBeforeMapping);

        // Due-правила (включая "due_date: null — снять срок") применяются только если
        // запрос реально нёс хотя бы один due-ключ. Иначе — поле отсутствовало в теле
        // запроса целиком, entity уже восстановлена к состоянию "было" строкой выше,
        // трогать reminderSentAt не нужно (applyDueRules — единственное место, которое
        // его меняет).
        if (todoDto.isDueFieldsProvided()) {
            applyDueRules(todoDto, existingTodo);
        }

        // Логика completedAt и completorUser
        if (!wasDone && nowDone) {
            // Задача выполнена: проставляем время выполнения и исполнителя.
            // ВНИМАНИЕ: completorUserId сюда приходит НЕ из HTTP-тела — в TodoRequest такого
            // поля нет и маппер его не переносит, так что через REST значение всегда null.
            // Если поле когда-нибудь появится в TodoRequest, здесь нужна проверка членства
            // в списке (как в markAsDone, который берёт исполнителя из JWT): иначе перебором
            // completor_user_id можно вытащить расшифрованные имена чужих пользователей.
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
    public DueTodosResponse getDueTodos(Long requestingUserId) {
        LocalDate until = LocalDate.now().plusDays(UPCOMING_DAYS);
        List<Todo> todos = todoRepository.findWithDueVisibleToUser(requestingUserId, until);

        List<TodoResponse> overdue = new ArrayList<>();
        List<TodoResponse> today = new ArrayList<>();
        List<TodoResponse> upcoming = new ArrayList<>();

        for (Todo todo : todos) {
            ZoneId zone = todo.getDueTimezone() != null
                    ? ZoneId.of(todo.getDueTimezone()) : FALLBACK_ZONE;
            LocalDate todayInTaskZone = LocalDate.now(zone);
            TodoResponse response = todoMapper.toResponse(todoMapper.toDto(todo));

            if (todo.getDueDate().isBefore(todayInTaskZone)) {
                overdue.add(response);
            } else if (todo.getDueDate().isEqual(todayInTaskZone)) {
                today.add(response);
            } else {
                upcoming.add(response);
            }
        }
        return DueTodosResponse.builder().overdue(overdue).today(today).upcoming(upcoming).build();
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
        List<Todo> todos;
        if (userId.equals(requestingUserId)) {
            todos = todoRepository.findByUserId(userId);
        } else {
            // Чужие задачи отдаём только через ОБЩИЕ списки и только публичные. Без этой
            // отсечки выборка шла голым findByUserId, и любой держатель валидного токена
            // перебором GET /api/todos/user/{id} вычитывал задачи всех пользователей —
            // включая списки, куда его не приглашали.
            // Членство спрашиваем ПЕРВЫМ: пустой ответ обрывает запрос до похода в todo,
            // иначе атака стоила бы нам полного сканирования таблицы с расшифровкой имён.
            List<Long> sharedListIds = taskListUserRepository.findListIdsByUserId(requestingUserId);
            if (sharedListIds.isEmpty()) {
                return List.of();
            }
            todos = todoRepository.findByAuthorInListsVisibleToOthers(userId, sharedListIds);
        }
        log.debug("Загрузка задач для userId={}, requestingUserId={}, найдено: {}",
                userId, requestingUserId, todos.size());
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

    /**
     * Без @Transactional на весь метод: раньше один свип шёл в одной транзакции, и упавший
     * (или не закоммитившийся) markReminderSent откатывал отметки уже обработанных задач того
     * же прохода — следующий проход рассылал бы их заново (panel-review Task 8, Critical).
     * Каждая отметка коммитится сама по себе — REQUIRES_NEW на TodoRepository.markReminderSent.
     */
    @Override
    public int dispatchDueReminders() {
        Instant now = Instant.now();
        List<Todo> due = todoRepository.findDueForReminder(now, now.minus(STALE_AFTER));

        // Состояние каналов — одной строкой на свип, а не на получателя: иначе оператор,
        // разбирающий "почему не пришло", видит только "Обработано задач: N" и не отличает
        // закрытый флагом канал от отсутствия верифицированных получателей. Читаем флаг ОДИН
        // раз и передаём в notifyOne, чтобы строка не могла разойтись с поведением.
        // Про push пишем отдельно и не утверждаем за него: PushNotificationServiceImpl гасится
        // своим флагом молча, и при обоих закрытых каналах свип не шлёт НИЧЕГО, но всё равно
        // проставляет reminder_sent_at — напоминания теряются навсегда. Молчим, когда
        // рассылать нечего, иначе строка капает каждые 5 минут круглосуточно.
        boolean emailEnabled = flagStore.isEnabled(FeatureFlag.TODO_REMINDER_EMAIL);
        if (!due.isEmpty() && !emailEnabled) {
            log.info("[todo-reminder] Канал писем выключен флагом {}; push: {}",
                    FeatureFlag.TODO_REMINDER_EMAIL.getName(),
                    flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)
                            ? "включён" : "ВЫКЛЮЧЕН, напоминания не уходят никому");
        }

        // Один токен отписки на пользователя за весь свип: колонка User.unsubscribeToken общая
        // на пользователя, и issueUnsubscribeToken её перезаписывает. Без переиспользования
        // пользователь с двумя задачами в одном окне получил бы два письма, но рабочей была бы
        // только ссылка из последнего — предыдущая отвечала бы "неверный токен" (panel-review
        // Task 8, Important).
        Map<Long, String> unsubscribeTokens = new HashMap<>();
        // Имя списка на письмо — по listId через репозиторий, а не через todo.getTaskList():
        // без внешней @Transactional Todo из findDueForReminder detached, а связь LAZY —
        // обращение к ней роняет LazyInitializationException внутри try/catch письма, письмо
        // молча не уходит НИКОГДА (panel-review Task 8, повторный проход, Critical). Кэш —
        // чтобы не бить репозиторий на каждого получателя одного и того же списка.
        Map<Long, String> listNames = new HashMap<>();

        for (Todo todo : due) {
            try {
                for (User recipient : resolveRecipients(todo)) {
                    notifyOne(todo, recipient, emailEnabled, unsubscribeTokens, listNames);
                }
            } catch (Exception e) {
                // Падение одной задачи не должно рвать весь проход.
                log.warn("[todo-reminder] Ошибка обработки задачи id={}: {}", todo.getId(), e.getMessage());
            }
            // Отметка по факту ПОПЫТКИ, а не успеха: иначе упавший канал заставит планировщик
            // слать одно и то же каждые 5 минут. Собственный try/catch — сбой самой отметки
            // (таймаут, deadlock) теряет только эту задачу, а не прерывает проход остальных.
            try {
                todoRepository.markReminderSent(todo.getId(), LocalDateTime.now());
            } catch (Exception e) {
                log.warn("[todo-reminder] Не удалось отметить задачу id={} как обработанную: {}",
                        todo.getId(), e.getMessage());
            }
        }
        return due.size();
    }

    /** Приватную задачу видит только автор — она не уходит участникам ни при каком scope. */
    private List<User> resolveRecipients(Todo todo) {
        if (todo.getIsPrivate() || todo.getReminderScope() == ReminderScope.SELF) {
            return userRepository.findById(todo.getUserId()).map(List::of).orElseGet(List::of);
        }
        return taskListUserRepository.findByIdListId(todo.getListId()).stream()
                .map(TaskListUser::getUser)
                .toList();
    }

    /**
     * Дата и время срока в человекочитаемом виде для текста push/письма — без него
     * напоминание с недельным запасом молча выглядело бы как "срок сегодня" (panel-review,
     * финальное ревью ветки). Числовой формат dd.MM.yyyy HH:mm одинаково однозначен
     * что для RU, что для EN — не зависит от локали получателя, поэтому один и тот же
     * текст можно передать в оба канала без per-token форматирования.
     */
    private static final DateTimeFormatter DUE_AT_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private String formatDueAt(Todo todo) {
        return LocalDateTime.of(todo.getDueDate(), todo.getDueTime()).format(DUE_AT_FORMATTER);
    }

    private void notifyOne(Todo todo, User recipient, boolean emailEnabled,
                            Map<Long, String> unsubscribeTokens, Map<Long, String> listNames) {
        String dueAt = formatDueAt(todo);
        try {
            pushNotificationService.sendTodoDuePush(
                    recipient.getId(), todo.getId(), todo.getListId(), todo.getName(), dueAt);
        } catch (Exception e) {
            log.warn("[todo-reminder] Push не отправлен userId={}: {}", recipient.getId(), e.getMessage());
        }

        // Канал писем закрыт отдельным флагом: напоминания запускаются push'ами, пока не
        // закрыты два решения по почте (общий токен отписки и отсутствие обратного включения
        // согласия). Проверка стоит ДО гейтов получателя и до выпуска токена — issueUnsubscribeToken
        // пишет в колонку, общую с маркетинговой рассылкой, и вхолостую его дёргать нельзя.
        // Значение приходит параметром: прочитано один раз на свип в dispatchDueReminders.
        if (!emailEnabled) {
            return;
        }
        if (!recipient.isEmailVerified() || !recipient.isTodoReminderEmailEnabled()) {
            return;
        }
        try {
            // Токен выпускаем только сейчас, непосредственно перед отправкой: колонка
            // User.unsubscribeToken общая с маркетинговой рассылкой, лишний выпуск инвалидирует
            // непрочитанные ссылки в уже доставленных письмах (owner-решение отложено).
            // Без computeIfAbsent: issueUnsubscribeToken теоретически может вернуть null,
            // а computeIfAbsent null не кэширует — на второй задаче того же пользователя
            // токен выпустился бы заново, снова перетирая колонку (panel-review Task 8, Minor).
            String token;
            if (unsubscribeTokens.containsKey(recipient.getId())) {
                token = unsubscribeTokens.get(recipient.getId());
            } else {
                token = userService.issueUnsubscribeToken(recipient.getId());
                unsubscribeTokens.put(recipient.getId(), token);
            }
            // Имя списка — по listId через репозиторий, не через todo.getTaskList() (LAZY,
            // Todo здесь detached — см. комментарий у listNames в dispatchDueReminders).
            String listName = listNames.computeIfAbsent(todo.getListId(),
                    id -> taskListRepository.findById(id).map(TaskList::getName).orElse(""));
            emailService.sendTodoDueEmail(recipient.getEmail(), recipient.getName(), todo.getName(),
                    listName, dueAt, recipient.getId(),
                    recipient.getPreferredEmailLocale(), token);
        } catch (Exception e) {
            log.warn("[todo-reminder] Письмо не отправлено userId={}: {}", recipient.getId(), e.getMessage());
        }
    }
}
