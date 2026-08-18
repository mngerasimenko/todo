package ru.mngerasimenko.todolist.scheduler;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.SubscriptionService;
import ru.mngerasimenko.todolist.service.SuggestionService;
import ru.mngerasimenko.todolist.service.TodoService;
import ru.mngerasimenko.todolist.service.TodoServiceImpl;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Тесты планировщика напоминаний о сроках задач.
 * Два уровня в одном файле (по образцу единого сценария из SDD-плана Task 8):
 * - {@link Dispatch} — планировщик дёргает сервис только при включённом флаге;
 * - {@link DispatchDueReminders} — сама логика рассылки (кому, каким каналом, что при сбое).
 */
@ExtendWith(MockitoExtension.class)
class TodoReminderSchedulerTest {

    /** Планировщик: единственная его обязанность — проверить флаг перед вызовом сервиса. */
    @Nested
    class Dispatch {

        @Mock
        private TodoService todoService;

        @Mock
        private FeatureFlagStore flagStore;

        @InjectMocks
        private TodoReminderScheduler scheduler;

        @Test
        void dispatch_FlagDisabled_DoesNothing() {
            when(flagStore.isEnabled(FeatureFlag.TODO_REMINDERS)).thenReturn(false);

            scheduler.dispatch();

            verifyNoInteractions(todoService);
        }
    }

    /**
     * Логика рассылки в TodoServiceImpl.dispatchDueReminders. TodoServiceImpl здесь —
     * реальный объект (не мок): проверяем настоящую логику resolveRecipients/notifyOne
     * поверх замоканных репозиториев и каналов доставки.
     */
    @Nested
    class DispatchDueReminders {

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
        private TodoMapper todoMapper;
        @Mock
        private SubscriptionService subscriptionService;
        @Mock
        private SuggestionService suggestionService;
        @Mock
        private EmailService emailService;
        @Mock
        private UserService userService;

        @InjectMocks
        private TodoServiceImpl todoService;

        @Test
        void dispatchDueReminders_ScopeSelf_NotifiesAuthorOnly() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L); // автор 10, список 86
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), eq(todo.getId()), eq(86L), any());
            verifyNoMoreInteractions(pushNotificationService);
        }

        @Test
        void dispatchDueReminders_ScopeAll_NotifiesAllMembers() {
            Todo todo = todoWithScope(ReminderScope.ALL, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(taskListUserRepository.findByIdListId(86L)).thenReturn(members(10L, 11L, 12L));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), any(), any(), any());
            verify(pushNotificationService).sendTodoDuePush(eq(11L), any(), any(), any());
            verify(pushNotificationService).sendTodoDuePush(eq(12L), any(), any(), any());
        }

        @Test
        void dispatchDueReminders_PrivateTodo_NeverNotifiesMembers() {
            Todo todo = todoWithScope(ReminderScope.ALL, 10L, 86L);
            todo.setIsPrivate(true);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), any(), any(), any());
            verifyNoMoreInteractions(pushNotificationService);
        }

        @Test
        void dispatchDueReminders_SendFails_StillMarksSent() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            doThrow(new RuntimeException("FCM недоступен"))
                    .when(pushNotificationService).sendTodoDuePush(any(), any(), any(), any());

            todoService.dispatchDueReminders();

            verify(todoRepository).markReminderSent(eq(todo.getId()), any());
        }

        @Test
        void dispatchDueReminders_EmailDisabled_SkipsEmail() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            User author = userWithEmail(10L, "a@test.ru");
            author.setTodoReminderEmailEnabled(false);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(userRepository.findById(10L)).thenReturn(Optional.of(author));

            todoService.dispatchDueReminders();

            verifyNoInteractions(emailService);
        }

        /**
         * Отдельно от «Send fails» (там падает канал ВНУТРИ notifyOne, он гасится локально):
         * здесь падает сам resolveRecipients (поиск участников списка) — исключение, долетающее
         * до внешнего try/catch в dispatchDueReminders. Ни один из шести тестов брифа этот путь
         * не проверяет, а правило "падение одной задачи не должно рвать весь проход" — из чек-листа.
         */
        @Test
        void dispatchDueReminders_ResolveRecipientsThrows_ContinuesWithNextTask() {
            Todo failing = todoWithScope(ReminderScope.ALL, 10L, 86L);
            Todo healthy = todoWithScope(ReminderScope.SELF, 20L, 87L);
            healthy.setId(2L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(failing, healthy));
            when(taskListUserRepository.findByIdListId(86L)).thenThrow(new RuntimeException("DB недоступна"));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(20L), eq(healthy.getId()), eq(87L), any());
            verify(todoRepository).markReminderSent(eq(failing.getId()), any());
            verify(todoRepository).markReminderSent(eq(healthy.getId()), any());
        }

        // === Хелперы фикстур ===

        /**
         * Задача со сроком и заданным scope/автором/списком. Заодно регистрирует автора
         * в userRepository (lenient — при ALL-сценарии этот стаб не используется, резолвинг
         * идёт через taskListUserRepository, а не через прямой поиск автора).
         */
        private Todo todoWithScope(ReminderScope scope, Long authorId, Long listId) {
            User author = userWithEmail(authorId, "author" + authorId + "@test.ru");
            lenient().when(userRepository.findById(authorId)).thenReturn(Optional.of(author));

            Todo todo = new Todo();
            todo.setId(1L);
            todo.setName("Полить цветы");
            todo.setUserId(authorId);
            TaskList taskList = new TaskList();
            taskList.setId(listId);
            taskList.setName("Дом");
            todo.setTaskList(taskList);
            todo.setReminderScope(scope);
            return todo;
        }

        /** Пользователь с подтверждённым email и включённым напоминанием — базовый случай. */
        private User userWithEmail(Long id, String email) {
            User user = new User();
            user.setId(id);
            user.setName("Пользователь " + id);
            user.setEmail(email);
            user.setEmailVerified(true);
            user.setTodoReminderEmailEnabled(true);
            return user;
        }

        /** Участники списка для сценария ALL — каждый со своим пользователем. */
        private List<TaskListUser> members(Long... userIds) {
            List<TaskListUser> result = new ArrayList<>();
            for (Long id : userIds) {
                TaskListUser member = new TaskListUser();
                member.setUser(userWithEmail(id, "member" + id + "@test.ru"));
                result.add(member);
            }
            return result;
        }
    }
}
