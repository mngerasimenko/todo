package ru.mngerasimenko.todolist.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нагрузочный тест: 5 потоков одновременно регистрируют пользователя с одинаковым именем.
 * Ожидаемый результат: ровно 1 пользователь создан, остальные получают IllegalArgumentException.
 */
@Tag("integration")
class CreateUserConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListUserRepository taskListUserRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    private static final String DUPLICATE_NAME = "concurrent-user";
    private static final String DUPLICATE_EMAIL = "concurrent@integration.ru";

    @BeforeEach
    void setUp() {
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createUserConcurrently_OnlyOneUserCreated() throws InterruptedException {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UserDto dto = new UserDto();
                    dto.setName(DUPLICATE_NAME);
                    dto.setEmail(DUPLICATE_EMAIL);
                    dto.setPassword("testPassword123");
                    userService.createUser(dto);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Одновременный старт всех потоков
        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS))
                .as("Все потоки должны завершиться за 30 секунд (BCrypt медленный)")
                .isTrue();
        executor.shutdown();

        // Ровно 1 пользователь с таким именем
        long userCount = userRepository.findAll().stream()
                .filter(u -> DUPLICATE_NAME.equals(u.getName()))
                .count();
        assertThat(userCount)
                .as("В БД должен быть ровно 1 пользователь с именем '%s'", DUPLICATE_NAME)
                .isEqualTo(1);

        // Потоки, которые проиграли гонку, должны получить IllegalArgumentException.
        // Текст сообщения обобщён в `UserServiceImpl.createUser` (DataIntegrityViolationException
        // ветка): «Не удалось создать аккаунт. Проверьте введённые данные и попробуйте снова».
        // Сделано умышленно, чтобы не подтверждать существование конкретного email.
        errors.forEach(e ->
                assertThat(e).as("Ожидался IllegalArgumentException, получен: %s", e.getClass().getName())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Не удалось создать аккаунт"));

        // Минимум один поток должен был завершиться успешно (без ошибки)
        assertThat(errors.size()).as("Не более 4 потоков должны были упасть").isLessThan(threads);
    }
}
