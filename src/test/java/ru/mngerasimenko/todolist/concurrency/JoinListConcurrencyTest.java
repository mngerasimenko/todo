package ru.mngerasimenko.todolist.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.TaskListService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нагрузочный тест: 10 потоков одновременно вступают в один список.
 * Ожидаемый результат: ровно 1 запись в task_list_user.
 */
@Tag("integration")
class JoinListConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TaskListService taskListService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    @Autowired
    private TaskListUserRepository taskListUserRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private TaskList testList;
    private static final String LIST_PASSWORD = "joinPassword123";
    private static final String LIST_NAME = "ConcurrencyTestList";

    @BeforeEach
    void setUp() {
        // Очищаем состояние в правильном порядке (FK constraints)
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();

        // Создаём пользователя-участника
        testUser = new User();
        testUser.setAuthId(UUID.randomUUID().toString());
        testUser.setEmail("join-test@integration.ru");
        testUser.setPassword(passwordEncoder.encode("userpass"));
        testUser.setName("joinTestUser");
        testUser = userRepository.save(testUser);

        // Создаём список (без создателя в task_list_user — просто пустой список для теста)
        testList = new TaskList(LIST_NAME, passwordEncoder.encode(LIST_PASSWORD));
        testList = taskListRepository.save(testList);
    }

    @AfterEach
    void tearDown() {
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void joinListConcurrently_OnlyOneEntryCreatedInDatabase() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        Long userId = testUser.getId();
        String listName = testList.getName();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    taskListService.joinList(listName, LIST_PASSWORD, userId);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Одновременный старт всех потоков
        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS))
                .as("Все потоки должны завершиться за 15 секунд")
                .isTrue();
        executor.shutdown();

        // Проверяем финальное состояние БД: ровно 1 запись участия
        long memberCount = taskListUserRepository.findByIdListId(testList.getId()).size();
        assertThat(memberCount)
                .as("В таблице task_list_user должна быть ровно 1 запись")
                .isEqualTo(1);

        // Все ошибки должны быть связаны с race condition (DataIntegrityViolation) — не NPE и не ClassCast
        errors.forEach(e ->
                assertThat(e).as("Недопустимый тип исключения: %s", e.getClass().getName())
                        .isNotInstanceOf(NullPointerException.class)
                        .isNotInstanceOf(ClassCastException.class));
    }
}
