package ru.mngerasimenko.todolist.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.TaskListService;
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
 * Нагрузочный тест: 10 потоков одновременно принимают приглашение в один список.
 * Ожидаемый результат: ровно 1 запись в task_list_user на каждого пользователя.
 */
@Tag("integration")
class JoinListConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TaskListService taskListService;

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

    private Long adminUserId;
    private Long joiningUserId;
    private String inviteToken;

    @BeforeEach
    void setUp() {
        // Создаём админа списка
        UserDto admin = UserDto.builder()
                .name("invite-admin")
                .email("invite-admin@integration.ru")
                .password("pass123")
                .build();
        UserDto createdAdmin = userService.createUser(admin);
        adminUserId = createdAdmin.getId();

        // Создаём список
        ListResponse list = taskListService.createList("InviteTestList", adminUserId);

        // Создаём приглашение
        InviteResponse invite = taskListService.createInvite(list.getId(), adminUserId, null);
        // Извлекаем raw-токен из ссылки
        inviteToken = invite.getInviteLink().substring(invite.getInviteLink().lastIndexOf("/") + 1);

        // Создаём пользователя, который будет вступать
        UserDto joiner = UserDto.builder()
                .name("invite-joiner")
                .email("invite-joiner@integration.ru")
                .password("pass123")
                .build();
        UserDto createdJoiner = userService.createUser(joiner);
        joiningUserId = createdJoiner.getId();
    }

    @AfterEach
    void tearDown() {
        todoRepository.deleteAll();
        taskListUserRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void acceptInvite_ConcurrentSameUser_OnlyOneRecord() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    taskListService.acceptInvite(inviteToken, joiningUserId);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(completed).isTrue();

        // Проверяем: ровно 1 запись участника (не дубликаты)
        long memberCount = taskListUserRepository.countByListId(
                taskListService.getListsByUserId(joiningUserId).get(0).getId()
        );
        // Админ + 1 вступивший = 2
        assertThat(memberCount).isEqualTo(2);
    }
}
