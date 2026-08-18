package ru.mngerasimenko.todolist.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест для колонок сроков задач (миграция 030): проверяет реальный round-trip
 * через настоящую PostgreSQL (типы date/time/timestamp, enum-mapping), а не H2 —
 * unit-тесты на H2 могли бы молча проглотить несовместимость типов.
 */
@Tag("integration")
class TodoDueQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void save_PersistsAllDueFields() {
        User user = createUser("due@test.ru");
        TaskList list = createList(user, "Дача");
        Todo todo = new Todo();
        todo.setName("Полить теплицу");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(user);
        todo.setTaskList(list);
        todo.setDueDate(LocalDate.of(2026, 7, 31));
        todo.setDueTime(LocalTime.of(18, 0));
        todo.setDueTimezone("Asia/Novosibirsk");
        todo.setRemindBeforeMinutes(1440);
        todo.setReminderScope(ReminderScope.ALL);

        Todo saved = todoRepository.saveAndFlush(todo);
        entityManager.clear();

        Todo loaded = todoRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(loaded.getDueTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(loaded.getDueTimezone()).isEqualTo("Asia/Novosibirsk");
        assertThat(loaded.getRemindBeforeMinutes()).isEqualTo(1440);
        assertThat(loaded.getReminderScope()).isEqualTo(ReminderScope.ALL);
        assertThat(loaded.getReminderSentAt()).isNull();
    }

    @Test
    void save_TodoWithoutDue_AppliesColumnDefaults() {
        Todo saved = todoRepository.saveAndFlush(newTodoWithoutDue());
        entityManager.clear();

        Todo loaded = todoRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getDueDate()).isNull();
        assertThat(loaded.getDueTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(loaded.getRemindBeforeMinutes()).isZero();
        assertThat(loaded.getReminderScope()).isEqualTo(ReminderScope.SELF);
    }

    // === Хелперы фикстур ===

    /**
     * Минимально-валидный пользователь для FK. email/authId уникальны через UUID —
     * тесты в singleton-контейнере (см. AbstractIntegrationTest) не пересекаются между собой.
     */
    private User createUser(String email) {
        User user = new User();
        user.setAuthId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setEmailHash(email); // без шифрования в тесте: hash = email, как в UserRepositoryTest
        user.setPassword("password123");
        user.setName("Due Test User");
        return userRepository.save(user);
    }

    private TaskList createList(User owner, String name) {
        TaskList list = new TaskList(name, owner);
        return taskListRepository.save(list);
    }

    /** Задача без явных due-полей — проверяет, что поля остаются на дефолтах сущности. */
    private Todo newTodoWithoutDue() {
        User user = createUser("no-due-" + UUID.randomUUID() + "@test.ru");
        TaskList list = createList(user, "Без срока");
        Todo todo = new Todo();
        todo.setName("Задача без срока");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(user);
        todo.setTaskList(list);
        return todo;
    }
}
