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

        todoService.updateTodo(1L, dto, 1L);

        assertThat(existing.getReminderSentAt()).isNull();
    }
}
