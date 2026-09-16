package ru.mngerasimenko.todolist.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.TaskListService;
import ru.mngerasimenko.todolist.service.TodoService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нагрузочный тест: 20 потоков одновременно отмечают одну задачу как выполненную.
 * Ожидаемый результат: хотя бы один вызов закоммичен и задача в done=true, а проигравшие гонку
 * падают только с ObjectOptimisticLockingFailureException (@Version) — без NPE и прочих ошибок.
 * Сам механизм @Version тест не доказывает: все потоки пишут одно и то же, и без версии
 * они просто закоммитились бы по очереди.
 */
@Tag("integration")
class MarkAsDoneConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TodoService todoService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    @Autowired
    private TaskListUserRepository taskListUserRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TaskListService taskListService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private Todo testTodo;

    @BeforeEach
    void setUp() {
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();

        // Создаём пользователя
        testUser = new User();
        testUser.setAuthId(UUID.randomUUID().toString());
        testUser.setEmail("done-test@integration.ru");
        testUser.setPassword(passwordEncoder.encode("userpass"));
        testUser.setName("doneTestUser");
        testUser = userRepository.save(testUser);

        // Список создаём через сервис, а не репозиторием: createList делает создателя участником
        // с ролью ADMIN. Фикстура, собранная в обход сервиса, этого членства не имела, и markAsDone
        // отвечал AccessDeniedException во всех потоках — гонки не было вовсе, а тест падал всегда.
        ListResponse list = taskListService.createList("DoneTestList", testUser.getId());
        TaskList testList = taskListRepository.findById(list.getId()).orElseThrow();

        // Создаём задачу со статусом done=false
        testTodo = new Todo();
        testTodo.setName("Concurrent Done Task");
        testTodo.setCreatedAt(LocalDateTime.now());
        testTodo.setDone(false);
        testTodo.setIsPrivate(false);
        testTodo.setUser(testUser);
        testTodo.setTaskList(testList);
        testTodo = todoRepository.save(testTodo);
    }

    @AfterEach
    void tearDown() {
        taskListUserRepository.deleteAll();
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void markAsDoneConcurrently_TodoIsDoneWithoutNPE() throws InterruptedException {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successes = new AtomicInteger();

        Long todoId = testTodo.getId();
        Long userId = testUser.getId();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    todoService.markAsDone(todoId, userId);
                    successes.incrementAndGet();
                } catch (Throwable e) {
                    // Throwable, а не Exception: Error из потока иначе осел бы в Future, который
                    // никто не читает, и тест остался бы зелёным при одном удачном потоке.
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

        // Порядок проверок важен: сначала — почему проигравшие упали, потом — был ли победитель,
        // и только потом итоговый done. Каждая из 20 транзакций пишет done=true, откатить уже
        // закоммиченную optimistic lock не может, поэтому done=false значит «не закоммитился никто».
        // Если проверять done первым, причина (например, AccessDeniedException у всех потоков)
        // теряется за безликим «expected true, but was false».
        assertThat(errors)
                .as("Проигравшие гонку допустимы только с ObjectOptimisticLockingFailureException")
                .allSatisfy(e -> assertThat(e).isInstanceOf(ObjectOptimisticLockingFailureException.class));
        assertThat(successes.get())
                .as("Хотя бы один markAsDone() должен выиграть гонку и закоммититься")
                .isPositive();

        Todo finalTodo = todoRepository.findById(todoId).orElseThrow();
        assertThat(finalTodo.isDone())
                .as("Задача должна быть помечена как выполненная (done=true)")
                .isTrue();
        assertThat(finalTodo.getCompletedAt())
                .as("completedAt должен быть установлен")
                .isNotNull();
    }
}
