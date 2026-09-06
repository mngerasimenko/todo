package ru.mngerasimenko.todolist.scheduler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
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
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        @Mock
        private FeatureFlagStore flagStore;

        @InjectMocks
        private TodoServiceImpl todoService;

        /**
         * Этот класс описывает МЕХАНИКУ доставки — кому уходит, сколько раз выпускается токен,
         * что происходит при detached-связи. Канал писем по умолчанию включён, иначе каждый
         * тест доставки молча уходил бы по ветке "канал закрыт" и проходил вхолостую.
         * Тест самого флага ниже переопределяет этот стаб явно.
         */
        @BeforeEach
        void enableEmailChannel() {
            lenient().when(flagStore.isEnabled(FeatureFlag.TODO_REMINDER_EMAIL)).thenReturn(true);
        }

        @Test
        void dispatchDueReminders_EmailChannelDisabled_SendsPushOnly() {
            // Ради этого флаг и заведён: напоминания запускаются push'ами, пока не закрыты два
            // решения по почте. Письмо не уходит даже получателю, у которого подтверждена почта
            // и включено согласие, — то есть гейт канала стоит выше гейтов получателя.
            when(flagStore.isEnabled(FeatureFlag.TODO_REMINDER_EMAIL)).thenReturn(false);
            // Push включён явно: без этого стаба мок отдаёт false, и диагностическая строка
            // свипа печатала бы "push ВЫКЛЮЧЕН" в тесте, который ниже проверяет, что push ушёл.
            when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), eq(todo.getId()), eq(86L), any(), any());
            verifyNoInteractions(emailService);
            // Токен отписки не выпускается вхолостую: колонка общая с маркетинговой рассылкой,
            // и лишний выпуск обесценил бы ссылку в уже доставленном письме.
            verifyNoInteractions(userService);
            // Позитивный контроль к этому тесту — dispatchDueReminders_EligibleUser_SendsEmail
            // ниже: те же гейты получателя, но канал открыт стабом из enableEmailChannel().
        }

        /**
         * Сторож диагностической строки свипа. Она существует ради одного вопроса оператора —
         * "почему не пришло" — и уже один раз соврала: первая версия утверждала "уходят только
         * push'ом", не проверяя push вовсе. Самый опасный случай — оба канала закрыты: свип
         * молча проставляет reminder_sent_at, напоминания теряются навсегда, и строка лога
         * остаётся единственным следом. Поэтому проверяется именно она, а не факт неотправки.
         */
        @Test
        void dispatchDueReminders_BothChannelsDisabled_LogsThatNobodyIsNotified() {
            when(flagStore.isEnabled(FeatureFlag.TODO_REMINDER_EMAIL)).thenReturn(false);
            when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(false);
            // Строится ДО when(...): todoWithScope сам регистрирует стабы, и вызов внутри
            // when(...) даёт UnfinishedStubbingException.
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            Logger logger = (Logger) LoggerFactory.getLogger(TodoServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                todoService.dispatchDueReminders();
            } finally {
                logger.detachAppender(appender);
            }

            String channels = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("Канал писем выключен"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("свип не сообщил о состоянии каналов"));
            assertTrue(channels.contains("ВЫКЛЮЧЕН, напоминания не уходят никому"),
                    "при обоих закрытых каналах строка обязана сказать, что не уходит ничего: " + channels);
        }

        /** Обратная ветка того же тернарника: письма закрыты, push жив — строка не должна пугать. */
        @Test
        void dispatchDueReminders_EmailChannelDisabledPushAlive_LogsPushIsOn() {
            when(flagStore.isEnabled(FeatureFlag.TODO_REMINDER_EMAIL)).thenReturn(false);
            when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            Logger logger = (Logger) LoggerFactory.getLogger(TodoServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                todoService.dispatchDueReminders();
            } finally {
                logger.detachAppender(appender);
            }

            String channels = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("Канал писем выключен"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("свип не сообщил о состоянии каналов"));
            assertTrue(channels.contains("push: включён"),
                    "при живом push строка обязана это сказать: " + channels);
        }

        @Test
        void dispatchDueReminders_ScopeSelf_NotifiesAuthorOnly() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L); // автор 10, список 86
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), eq(todo.getId()), eq(86L), any(), any());
            verifyNoMoreInteractions(pushNotificationService);
        }

        @Test
        void dispatchDueReminders_ScopeAll_NotifiesAllMembers() {
            Todo todo = todoWithScope(ReminderScope.ALL, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(taskListUserRepository.findByIdListId(86L)).thenReturn(members(10L, 11L, 12L));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), any(), any(), any(), any());
            verify(pushNotificationService).sendTodoDuePush(eq(11L), any(), any(), any(), any());
            verify(pushNotificationService).sendTodoDuePush(eq(12L), any(), any(), any(), any());
            // Ровно 3 push, ни одного лишнего/дублированного на участника.
            verifyNoMoreInteractions(pushNotificationService);
        }

        @Test
        void dispatchDueReminders_PrivateTodo_NeverNotifiesMembers() {
            Todo todo = todoWithScope(ReminderScope.ALL, 10L, 86L);
            todo.setIsPrivate(true);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            // Ловушка: у списка реально есть 3 участника. Если бы приватность не резалась
            // раньше scope, resolveRecipients ушёл бы за ними и push долетел бы всем троим.
            lenient().when(taskListUserRepository.findByIdListId(86L)).thenReturn(members(10L, 11L, 12L));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(10L), any(), any(), any(), any());
            verifyNoMoreInteractions(pushNotificationService);
            // Доказываем не только "push ушёл одному", а что путь к участникам вообще не тронут.
            verify(taskListUserRepository, never()).findByIdListId(any());
        }

        /**
         * Падение push гасится ЛОКАЛЬНЫМ try/catch внутри notifyOne и никогда не долетает до
         * внешнего try в dispatchDueReminders — то есть это НЕ тест на "падение одной задачи не
         * рвёт проход" (это проверяют ResolveRecipientsThrows... и MarkReminderSentThrows...
         * ниже). Здесь тест уже, чем предполагало исходное имя: он лишь показывает, что сбой
         * конкретно push-канала не мешает дойти до отметки на обычном (без исключения наружу)
         * пути возврата — этот путь одинаков что для finally, что для обычного оператора после
         * try/catch, поэтому регресс "markReminderSent вообще перестал вызываться на failure
         * push" он бы не поймал (panel-review Task 8, Important — переименовано и пояснено).
         */
        @Test
        void dispatchDueReminders_PushChannelThrows_MarkStillCalledOnNormalReturn() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            doThrow(new RuntimeException("FCM недоступен"))
                    .when(pushNotificationService).sendTodoDuePush(any(), any(), any(), any(), any());

            todoService.dispatchDueReminders();

            verify(todoRepository).markReminderSent(eq(todo.getId()), any());
        }

        @Test
        void dispatchDueReminders_UserConsentDisabled_SkipsEmail() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            User author = userWithEmail(10L, "a@test.ru");
            author.setTodoReminderEmailEnabled(false);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(userRepository.findById(10L)).thenReturn(Optional.of(author));

            todoService.dispatchDueReminders();

            verifyNoInteractions(emailService);
            // Токен не должен выпускаться, если письмо всё равно не уйдёт.
            verifyNoInteractions(userService);
        }

        @Test
        void dispatchDueReminders_EmailNotVerified_SkipsEmail() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            User author = userWithEmail(10L, "a@test.ru");
            author.setEmailVerified(false);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(userRepository.findById(10L)).thenReturn(Optional.of(author));

            todoService.dispatchDueReminders();

            verifyNoInteractions(emailService);
            verifyNoInteractions(userService);
        }

        @Test
        void dispatchDueReminders_EligibleUser_SendsEmail() {
            Todo todo = todoWithScope(ReminderScope.SELF, 10L, 86L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(userService.issueUnsubscribeToken(10L)).thenReturn("unsub-token");
            // Имя списка идёт через taskListRepository.findById, не через todo.getTaskList()
            // (LAZY, Todo из findDueForReminder detached) — стаб делает проверку реальной.
            when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Дом")));

            todoService.dispatchDueReminders();

            verify(emailService).sendTodoDueEmail(eq("author10@test.ru"), eq("Пользователь 10"),
                    eq(todo.getName()), eq("Дом"), any(), eq(10L), any(), eq("unsub-token"));
        }

        /**
         * Критическая находка ре-ревью: без внешней @Transactional Todo из findDueForReminder
         * detached, а Todo.taskList — LAZY. todo.getTaskList().getName() внутри письма ронял
         * LazyInitializationException, которую тут же глотал try/catch письма — письмо не
         * уходило НИКОГДА ни одному получателю, и это молча терялось в логе "не отправлено".
         * Мок Todo здесь не даёт коду ни единого шанса позвать getTaskList(): стаб этого
         * геттера кидает LazyInitializationException — если регрессия вернёт вызов, письмо
         * не отправится и verify(emailService)... ниже провалится тем же способом, что и в
         * проде.
         */
        @Test
        void dispatchDueReminders_TaskListAssociationDetached_StillSendsEmail() {
            Todo todo = mock(Todo.class);
            when(todo.getId()).thenReturn(1L);
            when(todo.getName()).thenReturn("Полить цветы");
            when(todo.getUserId()).thenReturn(10L);
            when(todo.getListId()).thenReturn(86L);
            when(todo.getIsPrivate()).thenReturn(false);
            when(todo.getReminderScope()).thenReturn(ReminderScope.SELF);
            when(todo.getDueDate()).thenReturn(LocalDate.of(2026, 8, 25));
            when(todo.getDueTime()).thenReturn(LocalTime.of(9, 0));
            lenient().when(todo.getTaskList()).thenThrow(
                    new LazyInitializationException("could not initialize proxy - no Session"));

            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(todo));
            when(userRepository.findById(10L)).thenReturn(Optional.of(userWithEmail(10L, "author10@test.ru")));
            when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Дом")));
            when(userService.issueUnsubscribeToken(10L)).thenReturn("unsub-token");

            todoService.dispatchDueReminders();

            verify(emailService).sendTodoDueEmail(eq("author10@test.ru"), eq("Пользователь 10"),
                    eq("Полить цветы"), eq("Дом"), any(), eq(10L), any(), eq("unsub-token"));
            verify(todo, never()).getTaskList();
        }

        /**
         * Колонка User.unsubscribeToken общая на пользователя — issueUnsubscribeToken её
         * перезаписывает при каждом вызове. Без переиспользования в рамках свипа пользователь
         * с двумя задачами в одном окне получил бы два письма, но рабочей осталась бы только
         * ссылка из последнего (panel-review Task 8, Important).
         */
        @Test
        void dispatchDueReminders_SameUserTwoTasks_IssuesUnsubscribeTokenOnce() {
            Todo first = todoWithScope(ReminderScope.SELF, 10L, 86L);
            Todo second = todoWithScope(ReminderScope.SELF, 10L, 86L);
            second.setId(2L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(first, second));
            when(userService.issueUnsubscribeToken(10L)).thenReturn("unsub-token");

            todoService.dispatchDueReminders();

            verify(userService, times(1)).issueUnsubscribeToken(10L);
            verify(emailService, times(2)).sendTodoDueEmail(
                    any(), any(), any(), any(), any(), eq(10L), any(), eq("unsub-token"));
        }

        /**
         * Ни один из тестов брифа этот путь не проверяет, а правило "падение одной задачи не
         * должно рвать весь проход" — из чек-листа. Исключение из resolveRecipients долетает
         * до внешнего try/catch в dispatchDueReminders.
         */
        @Test
        void dispatchDueReminders_ResolveRecipientsThrows_ContinuesWithNextTask() {
            Todo failing = todoWithScope(ReminderScope.ALL, 10L, 86L);
            Todo healthy = todoWithScope(ReminderScope.SELF, 20L, 87L);
            healthy.setId(2L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(failing, healthy));
            when(taskListUserRepository.findByIdListId(86L)).thenThrow(new RuntimeException("DB недоступна"));

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(20L), eq(healthy.getId()), eq(87L), any(), any());
            verify(todoRepository).markReminderSent(eq(failing.getId()), any());
            verify(todoRepository).markReminderSent(eq(healthy.getId()), any());
        }

        /**
         * Критическая находка ревью: раньше весь свип шёл в одной @Transactional — упавшая
         * отметка ОДНОЙ задачи (таймаут, deadlock) прервала бы метод и откатила бы уже
         * закоммиченные отметки предыдущих задач того же прохода. Теперь отметка — в
         * собственном try/catch и в собственной транзакции (REQUIRES_NEW на репозитории):
         * сбой первой задачи не должен помешать обработке и отметке второй.
         */
        @Test
        void dispatchDueReminders_MarkReminderSentThrows_ContinuesWithNextTask() {
            Todo failing = todoWithScope(ReminderScope.SELF, 10L, 86L);
            Todo healthy = todoWithScope(ReminderScope.SELF, 20L, 87L);
            healthy.setId(2L);
            when(todoRepository.findDueForReminder(any(), any())).thenReturn(List.of(failing, healthy));
            doThrow(new RuntimeException("Deadlock"))
                    .when(todoRepository).markReminderSent(eq(failing.getId()), any());

            todoService.dispatchDueReminders();

            verify(pushNotificationService).sendTodoDuePush(eq(20L), eq(healthy.getId()), eq(87L), any(), any());
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
            todo.setDueDate(LocalDate.of(2026, 8, 25));
            todo.setDueTime(LocalTime.of(9, 0));
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

        /** Список с заданным именем — для стаба taskListRepository.findById в письме. */
        private TaskList listNamed(Long id, String name) {
            TaskList list = new TaskList();
            list.setId(id);
            list.setName(name);
            return list;
        }
    }
}
