package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.dto.TodoDto;
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

    private TodoServiceImpl todoService;

    private User testUser;
    private TaskList testTaskList;

    @BeforeEach
    void setUp() {
        // Настоящий маппер: у TodoMapper нет зависимостей, конструировать вручную безопасно.
        TodoMapper realMapper = new TodoMapper();
        todoService = new TodoServiceImpl(todoRepository, userRepository, taskListRepository,
                taskListUserRepository, pushNotificationService, emailService, userService, realMapper,
                subscriptionService, suggestionService);

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
}
