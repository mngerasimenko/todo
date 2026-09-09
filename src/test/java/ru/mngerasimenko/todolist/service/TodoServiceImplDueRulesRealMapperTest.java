package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.mngerasimenko.todolist.dto.TodoDto;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * В основном {@link TodoServiceImplTest} {@link TodoMapper} замокирован ради изоляции
 * остальных тестов сервиса — но это делает невидимым один конкретный регресс: реальный
 * {@code updateEntityFromDto} безусловно копирует due-поля из dto в entity, а мок этого
 * не делает. Из-за этого снимок/восстановление в {@code TodoServiceImpl.updateTodo}
 * (фикс из ревью Task 3) в основном тестовом классе всегда "восстанавливает" то, что
 * мок и так не менял — тест там прошёл бы одинаково с фиксом и без него.
 * <p>
 * Здесь {@link TodoMapper} — настоящая реализация (у неё нет зависимостей), поэтому
 * связка "маппер безусловно копирует / applyDueRules сравнивает до и после" проверяется
 * так, как она реально работает в проде.
 */
@ExtendWith(MockitoExtension.class)
class TodoServiceImplDueRulesRealMapperTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskListRepository taskListRepository;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private EmailService emailService;

    @Mock
    private UserService userService;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private SuggestionService suggestionService;

    @Mock
    private FeatureFlagStore flagStore;

    private TodoServiceImpl todoService;

    private User testUser;
    private TaskList testTaskList;

    @BeforeEach
    void setUp() {
        // Настоящий маппер: у TodoMapper нет зависимостей, конструировать вручную безопасно.
        TodoMapper realMapper = new TodoMapper();
        todoService = new TodoServiceImpl(todoRepository, userRepository, taskListRepository,
                taskListUserRepository, pushNotificationService, emailService, userService, realMapper,
                subscriptionService, suggestionService, flagStore);

        testUser = new User();
        testUser.setId(1L);
        testUser.setName("testuser");

        testTaskList = new TaskList("TestList", testUser);
        testTaskList.setId(1L);
    }

    @Test
    void updateTodo_DueMomentChangedWithRealMapper_ClearsReminderSentAt() {
        Todo existing = new Todo();
        existing.setId(1L);
        existing.setName("Полить теплицу");
        existing.setDone(false);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUser(testUser);
        existing.setTaskList(testTaskList);
        existing.setDueDate(LocalDate.of(2026, 7, 31));
        existing.setDueTime(LocalTime.of(9, 0));
        existing.setDueTimezone("Europe/Moscow");
        existing.setRemindBeforeMinutes(0);
        existing.setReminderScope(ReminderScope.SELF);
        existing.setReminderSentAt(LocalDateTime.now().minusHours(1));

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoDto dto = new TodoDto();
        dto.setName("Полить теплицу");
        dto.setUserId(1L);
        dto.setDone(false);
        dto.setDueDate(LocalDate.of(2026, 8, 5));
        dto.setDueTime(LocalTime.of(9, 0));
        dto.setDueTimezone("Europe/Moscow");
        dto.setRemindBeforeMinutes(0);
        dto.setReminderScope(ReminderScope.SELF);
        dto.setDueFieldsProvided(true);

        todoService.updateTodo(1L, dto, 1L);

        assertThat(existing.getReminderSentAt()).isNull();
    }

    /**
     * Автор задачи неизменен — с НАСТОЯЩИМ маппером. В {@link TodoServiceImplTest} маппер
     * замокирован, поэтому там {@code updateEntityFromDto} — пустышка: если бы кто-то
     * скопировал в него строку {@code todo.setUserId(dto.getUserId())} из {@code toEntity},
     * ни тест сервиса (маппер мокирован), ни тест контроллера (сервис мокирован) этого бы
     * не заметили, и переназначение автора вернулось бы через заднюю дверь.
     */
    @Test
    void updateTodo_SpoofedUserIdWithRealMapper_KeepsOriginalAuthor() {
        Todo existing = new Todo();
        existing.setId(1L);
        existing.setName("Полить теплицу");
        existing.setDone(false);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUser(testUser);
        existing.setTaskList(testTaskList);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoDto dto = new TodoDto();
        dto.setName("Полить теплицу");
        dto.setUserId(999L);
        dto.setDone(false);

        TodoDto result = todoService.updateTodo(1L, dto, 1L);

        assertThat(existing.getUser()).isSameAs(testUser);
        assertThat(existing.getUserId()).isEqualTo(1L);
        // Ответ клиенту тоже несёт прежнего автора, а не подсунутого
        assertThat(result.getUserId()).isEqualTo(1L);
    }

    /**
     * CRITICAL из финального ревью ветки: оба выпущенных клиента (веб-форма и Android
     * TodoRequest) шлют обновление без единого due-ключа вообще. С реальным маппером
     * updateEntityFromDto безусловно копирует due-поля из dto (все null) в entity, а
     * applyDueRules видел бы dueDate==null и стирал бы всё — задача бы молча теряла срок
     * при простом переименовании. dueFieldsProvided=false (по умолчанию, т.к. в тесте
     * ни один due-сеттер не вызван) должен оставить существующие due-данные нетронутыми.
     */
    @Test
    void updateTodo_NoDueKeysInPayload_PreservesExistingDueData() {
        Todo existing = new Todo();
        existing.setId(1L);
        existing.setName("Полить теплицу");
        existing.setDone(false);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUser(testUser);
        existing.setTaskList(testTaskList);
        existing.setDueDate(LocalDate.of(2026, 7, 31));
        existing.setDueTime(LocalTime.of(18, 0));
        existing.setDueTimezone("Asia/Novosibirsk");
        existing.setRemindBeforeMinutes(1440);
        existing.setReminderScope(ReminderScope.ALL);
        LocalDateTime sentAt = LocalDateTime.now().minusHours(1);
        existing.setReminderSentAt(sentAt);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        // Типичный payload веб-формы / текущего Android TodoRequest: только name/userId/done —
        // ни один due-сеттер не вызывался, dueFieldsProvided остаётся false по умолчанию.
        TodoDto dto = new TodoDto();
        dto.setName("Полить теплицу (переименовано)");
        dto.setUserId(1L);
        dto.setDone(false);

        todoService.updateTodo(1L, dto, 1L);

        assertThat(existing.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(existing.getDueTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(existing.getDueTimezone()).isEqualTo("Asia/Novosibirsk");
        assertThat(existing.getRemindBeforeMinutes()).isEqualTo(1440);
        assertThat(existing.getReminderScope()).isEqualTo(ReminderScope.ALL);
        assertThat(existing.getReminderSentAt()).isEqualTo(sentAt);
    }

    /**
     * Контраст с тестом выше: та же существующая задача, но payload явно несёт
     * {@code due_date: null} (dueFieldsProvided=true, dueDate=null) — это должно
     * по-прежнему полностью очищать срок, как и до фикса.
     */
    @Test
    void updateTodo_ExplicitDueDateNullWithRealMapper_ClearsDueData() {
        Todo existing = new Todo();
        existing.setId(1L);
        existing.setName("Полить теплицу");
        existing.setDone(false);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUser(testUser);
        existing.setTaskList(testTaskList);
        existing.setDueDate(LocalDate.of(2026, 7, 31));
        existing.setDueTime(LocalTime.of(18, 0));
        existing.setDueTimezone("Asia/Novosibirsk");
        existing.setRemindBeforeMinutes(1440);
        existing.setReminderScope(ReminderScope.ALL);
        existing.setReminderSentAt(LocalDateTime.now().minusHours(1));

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoDto dto = new TodoDto();
        dto.setName("Полить теплицу");
        dto.setUserId(1L);
        dto.setDone(false);
        dto.setDueDate(null);
        dto.setDueFieldsProvided(true);

        todoService.updateTodo(1L, dto, 1L);

        assertThat(existing.getDueDate()).isNull();
        assertThat(existing.getDueTimezone()).isNull();
        assertThat(existing.getDueTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(existing.getRemindBeforeMinutes()).isZero();
        assertThat(existing.getReminderScope()).isEqualTo(ReminderScope.SELF);
        assertThat(existing.getReminderSentAt()).isNull();
    }

    /**
     * Участники списка о правке иначе не узнают: push'и есть на создание задачи и на её
     * выполнение, а на изменение имени или срока не было ни одного. У соседа оставалась строка
     * в состоянии «до правки» — и напоминание о сроке приходило про срок, которого в его копии
     * задачи ещё не было (баг с прода 08.09.2026).
     */
    @Test
    void updateTodo_DueChanged_SendsSyncPush() {
        Todo existing = sharedTodo();
        existing.setDueDate(LocalDate.of(2026, 7, 31));
        // Пояс фиксируем с обеих сторон намеренно: иначе applyDueRules подставит дефолтный,
        // изменятся ДВА поля, и тест был бы зелёным по двум независимым причинам — удаление
        // любого одного сравнения из пяти он бы пережил.
        existing.setDueTimezone("Europe/Moscow");
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        TodoDto dto = dtoFor(existing);
        dto.setDueDate(LocalDate.of(2026, 8, 5));
        dto.setDueTime(LocalTime.of(9, 0));
        dto.setDueTimezone("Europe/Moscow");
        dto.setRemindBeforeMinutes(0);
        dto.setReminderScope(ReminderScope.SELF);
        dto.setDueFieldsProvided(true);

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
    }

    @Test
    void updateTodo_NameChanged_SendsSyncPush() {
        Todo existing = sharedTodo();
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        TodoDto dto = dtoFor(existing);
        dto.setName("Полить огурцы");

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
    }

    /**
     * Холостой PUT рассылку не порождает. Клиенты шлют обновление целиком на каждое действие,
     * и без сравнения три подряд правки срока дали бы участникам три перечитывания списка.
     */
    @Test
    void updateTodo_NothingChanged_DoesNotSendSyncPush() {
        Todo existing = sharedTodo();
        // lenient: до флага условие не доходит (короткое замыкание), но БЕЗ этой заглушки флаг
        // по умолчанию false — и тест проходил бы именно из-за него, а не из-за того, что
        // проверяет. Мутация «changed всегда true» иначе осталась бы зелёной.
        lenient().when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        todoService.updateTodo(7L, dtoFor(existing), 1L);

        verify(pushNotificationService, never()).notifyTodoUpdated(anyLong(), anyLong(), anyLong());
    }

    /**
     * Выключенный флаг — рассылки нет. Обязательная вторая ветка: правила проекта требуют
     * теста на оба положения флага, иначе «выключить» остаётся непроверенным обещанием.
     */
    @Test
    void updateTodo_SyncPushFlagDisabled_DoesNotSendSyncPush() {
        Todo existing = sharedTodo();
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(false);

        TodoDto dto = dtoFor(existing);
        dto.setName("Полить огурцы");

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService, never()).notifyTodoUpdated(anyLong(), anyLong(), anyLong());
    }

    /**
     * Задача стала общей — участникам её надо показать. Меняется ТОЛЬКО приватность, поэтому
     * без учёта этого поля в условии «реально изменилось» рассылки бы не было вовсе.
     */
    @Test
    void updateTodo_BecamePublic_SendsSyncPush() {
        Todo existing = sharedTodo();
        existing.setIsPrivate(true);
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        TodoDto dto = dtoFor(existing);
        dto.setIsPrivate(false);

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
    }

    /**
     * Задача стала приватной — участникам надо УБРАТЬ её из списка. Молчание оставило бы у них
     * строку, которую им больше нельзя видеть, а тап по ней вернул бы 403.
     */
    @Test
    void updateTodo_BecamePrivate_SendsSyncPush() {
        Todo existing = sharedTodo();
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        TodoDto dto = dtoFor(existing);
        dto.setIsPrivate(true);

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
    }

    /** Приватная задача остальным участникам не видна — сообщать им о её правках нечего. */
    @Test
    void updateTodo_PrivateTodo_DoesNotSendSyncPush() {
        Todo existing = sharedTodo();
        // См. соседний тест: без заглушки флага проверка приватности не проверялась бы вовсе.
        lenient().when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);
        existing.setIsPrivate(true);

        TodoDto dto = dtoFor(existing);
        dto.setName("Полить огурцы");
        dto.setIsPrivate(true);

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService, never()).notifyTodoUpdated(anyLong(), anyLong(), anyLong());
    }

    /**
     * Задача в общем списке, автор — testUser; репозитории застабаны под updateTodo.
     *
     * Идентификаторы РАЗНЫЕ намеренно: задача 7, список 3, редактор 1. У `notifyTodoUpdated`
     * три параметра типа Long подряд, и с одинаковыми значениями перестановка любых двух
     * прошла бы зелёной.
     */
    private Todo sharedTodo() {
        TaskList sharedList = new TaskList("SharedList", testUser);
        sharedList.setId(3L);

        Todo existing = new Todo();
        existing.setId(7L);
        existing.setName("Полить теплицу");
        existing.setDone(false);
        existing.setIsPrivate(false);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUser(testUser);
        existing.setTaskList(sharedList);
        existing.setDueTime(LocalTime.of(9, 0));
        existing.setRemindBeforeMinutes(0);
        existing.setReminderScope(ReminderScope.SELF);

        when(todoRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(taskListUserRepository.findByIdListIdAndIdUserId(3L, 1L))
                .thenReturn(Optional.of(new TaskListUser(sharedList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));
        // Флаг стабится не здесь, а в тестах: до него доходит не каждый путь (условие
        // короткозамыкается), и лишняя заглушка уронила бы strict-stubs.
        return existing;
    }

    /** Тело запроса, повторяющее текущее состояние задачи: изменения задают поверх него. */
    private TodoDto dtoFor(Todo todo) {
        TodoDto dto = new TodoDto();
        dto.setName(todo.getName());
        dto.setUserId(1L);
        dto.setDone(todo.isDone());
        dto.setIsPrivate(todo.getIsPrivate());
        return dto;
    }

    /**
     * Боевая ветка: с активной транзакцией отправка обязана уйти ТОЛЬКО после коммита.
     *
     * В остальных тестах синхронизация не активна, и все они идут по ветке else — то есть
     * продовый путь не исполняется ни разу, и забытый registerSynchronization остался бы
     * зелёным. Здесь синхронизация поднимается руками.
     */
    @Test
    void updateTodo_WithActiveTransaction_SendsSyncPushOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Todo existing = sharedTodo();
            when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

            TodoDto dto = dtoFor(existing);
            dto.setName("Полить огурцы");

            todoService.updateTodo(7L, dto, 1L);

            // До коммита — молчим.
            verify(pushNotificationService, never()).notifyTodoUpdated(anyLong(), anyLong(), anyLong());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Смена галочки через PUT. Путь живой: офлайн-очередь Android кладёт `done` в тело PUT,
     * а не в отдельный PATCH, — и без этого терма в условии участники о ней не узнают.
     * Прогоняем через БОЕВУЮ ветку с активной транзакцией.
     */
    @Test
    void updateTodo_DoneChanged_SendsSyncPushAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Todo existing = sharedTodo();
            when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

            TodoDto dto = dtoFor(existing);
            dto.setDone(true);

            todoService.updateTodo(7L, dto, 1L);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Изменился только запас времени — остальные четыре due-поля те же. Без отдельного терма
     * в сравнении такая правка прошла бы молча, а участнику она меняет момент напоминания.
     */
    @Test
    void updateTodo_OnlyRemindBeforeChanged_SendsSyncPush() {
        Todo existing = sharedTodo();
        existing.setDueDate(LocalDate.of(2026, 8, 5));
        existing.setDueTimezone("Europe/Moscow");
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        TodoDto dto = dtoFor(existing);
        dto.setDueDate(LocalDate.of(2026, 8, 5));
        dto.setDueTime(LocalTime.of(9, 0));
        dto.setDueTimezone("Europe/Moscow");
        dto.setRemindBeforeMinutes(60);
        dto.setReminderScope(ReminderScope.SELF);
        dto.setDueFieldsProvided(true);

        todoService.updateTodo(7L, dto, 1L);

        verify(pushNotificationService).notifyTodoUpdated(3L, 1L, 7L);
    }
}
