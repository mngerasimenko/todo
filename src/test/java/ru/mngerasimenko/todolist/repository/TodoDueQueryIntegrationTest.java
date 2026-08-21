package ru.mngerasimenko.todolist.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест для колонок сроков задач (миграция 030): проверяет реальный round-trip
 * через настоящую PostgreSQL (типы date/time/timestamp, enum-mapping), а не H2 —
 * unit-тесты на H2 могли бы молча проглотить несовместимость типов.
 * <p>
 * Также покрывает {@link TodoRepository#findWithDueVisibleToUser} (Task 9, экран «Сегодня»):
 * контроллерные тесты этот запрос не трогают, там TodoService замокан целиком.
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
    private TaskListUserRepository taskListUserRepository;

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

    @Test
    void findDueForReminder_ReturnsRipe_SkipsFuture() {
        // 18:00 в Новосибирске = 11:00 UTC
        Todo ripe = saveTodoWithDue(LocalDate.of(2026, 7, 31), LocalTime.of(18, 0), "Asia/Novosibirsk", 0);
        Todo future = saveTodoWithDue(LocalDate.of(2026, 12, 31), LocalTime.of(18, 0), "Asia/Novosibirsk", 0);
        Instant now = LocalDateTime.of(2026, 7, 31, 11, 30).toInstant(ZoneOffset.UTC);

        List<Todo> due = todoRepository.findDueForReminder(now, now.minus(1, ChronoUnit.DAYS));

        assertThat(due).extracting(Todo::getId).containsExactly(ripe.getId());
    }

    @Test
    void findDueForReminder_AppliesLeadTime() {
        // срок 31 июля 09:00 МСК, запас сутки -> момент 30 июля 09:00 МСК = 06:00 UTC
        Todo todo = saveTodoWithDue(LocalDate.of(2026, 7, 31), LocalTime.of(9, 0), "Europe/Moscow", 1440);
        Instant now = LocalDateTime.of(2026, 7, 30, 6, 1).toInstant(ZoneOffset.UTC);

        assertThat(todoRepository.findDueForReminder(now, now.minus(1, ChronoUnit.DAYS)))
                .extracting(Todo::getId).containsExactly(todo.getId());
    }

    @Test
    void findDueForReminder_SkipsDoneSentAndStale() {
        Todo done = saveTodoWithDue(LocalDate.of(2026, 7, 31), LocalTime.of(9, 0), "Europe/Moscow", 0);
        done.setDone(true);
        todoRepository.saveAndFlush(done);

        Todo alreadySent = saveTodoWithDue(LocalDate.of(2026, 7, 31), LocalTime.of(9, 0), "Europe/Moscow", 0);
        alreadySent.setReminderSentAt(LocalDateTime.now());
        todoRepository.saveAndFlush(alreadySent);

        Todo stale = saveTodoWithDue(LocalDate.of(2026, 7, 1), LocalTime.of(9, 0), "Europe/Moscow", 0);

        Instant now = LocalDateTime.of(2026, 7, 31, 10, 0).toInstant(ZoneOffset.UTC);
        List<Todo> due = todoRepository.findDueForReminder(now, now.minus(1, ChronoUnit.DAYS));

        assertThat(due).extracting(Todo::getId)
                .doesNotContain(done.getId(), alreadySent.getId(), stale.getId());
    }

    @Test
    void findDueForReminder_MidnightBoundary_DoesNotShiftByDay() {
        // 00:00 1 августа в Москве = 21:00 31 июля UTC
        Todo todo = saveTodoWithDue(LocalDate.of(2026, 8, 1), LocalTime.of(0, 0), "Europe/Moscow", 0);
        Instant beforeMidnight = LocalDateTime.of(2026, 7, 31, 20, 59).toInstant(ZoneOffset.UTC);
        Instant afterMidnight = LocalDateTime.of(2026, 7, 31, 21, 1).toInstant(ZoneOffset.UTC);

        assertThat(todoRepository.findDueForReminder(beforeMidnight, beforeMidnight.minus(1, ChronoUnit.DAYS)))
                .isEmpty();
        assertThat(todoRepository.findDueForReminder(afterMidnight, afterMidnight.minus(1, ChronoUnit.DAYS)))
                .extracting(Todo::getId).containsExactly(todo.getId());
    }

    /**
     * markReminderSent — единственная защита от повторной отправки. Не мок: настоящий native
     * UPDATE против настоящей findDueForReminder, второй проход тем же окном (now/staleBefore)
     * должен перестать видеть задачу — иначе планировщик слал бы её на каждом цикле.
     * <p>
     * Без {@code @Transactional} на тесте: markReminderSent теперь сам несёт
     * {@code @Transactional(propagation = REQUIRES_NEW)} (panel-review Task 8, Critical) и
     * коммитится на отдельном соединении. Обёрни тест в свою транзакцию — вставленная задача
     * осталась бы некоммиченной и невидимой той отдельной REQUIRES_NEW-транзакции: UPDATE
     * молча задел бы 0 строк, и тест зафейлился бы не там, где думаешь. Задача остаётся в БД
     * после теста — как и у соседних тестов файла, изоляция через уникальный UUID в фикстуре,
     * а не через rollback.
     */
    @Test
    void markReminderSent_ThenFindDueForReminder_ExcludesTask() {
        Todo todo = saveTodoWithDue(LocalDate.of(2026, 7, 31), LocalTime.of(9, 0), "Europe/Moscow", 0);
        Instant now = LocalDateTime.of(2026, 7, 31, 10, 0).toInstant(ZoneOffset.UTC);
        Instant staleBefore = now.minus(1, ChronoUnit.DAYS);

        // Первый проход планировщика видит созревшую задачу.
        assertThat(todoRepository.findDueForReminder(now, staleBefore))
                .extracting(Todo::getId).contains(todo.getId());

        todoRepository.markReminderSent(todo.getId(), LocalDateTime.now());
        entityManager.clear();

        // Второй проход тем же окном — задача уже отмечена, повторной отправки не будет.
        assertThat(todoRepository.findDueForReminder(now, staleBefore))
                .extracting(Todo::getId).doesNotContain(todo.getId());
    }

    @Test
    void findDueForReminder_LeadTimeCrossesMonthBoundary() {
        // срок 1 августа 09:00 МСК, запас неделя -> момент 25 июля 09:00 МСК = 06:00 UTC
        Todo todo = saveTodoWithDue(LocalDate.of(2026, 8, 1), LocalTime.of(9, 0), "Europe/Moscow", 10080);
        Instant now = LocalDateTime.of(2026, 7, 25, 6, 1).toInstant(ZoneOffset.UTC);

        assertThat(todoRepository.findDueForReminder(now, now.minus(1, ChronoUnit.DAYS)))
                .extracting(Todo::getId).containsExactly(todo.getId());
    }

    /**
     * Приватность — единственное, что стоит между общим списком и чужой задачей на экране
     * «Сегодня»: приватная задача другого участника общего списка не должна попасть в выборку,
     * а его же публичная задача в том же списке — должна. Ни один тест на TodoService не бьёт
     * по этому SQL напрямую (там TodoService замокан), поэтому чек живёт здесь, против реальной БД.
     * <p>
     * {@code @Transactional} здесь (в отличие от соседних тестов файла) держит list/user/todo
     * в одном persistence context на весь тест: у каждого репозиторного вызова своя мини-транзакция,
     * и без общей обёртки {@code addMember} получает уже detached TaskList/User — Hibernate
     * не может связать {@code @MapsId}-ассоциацию TaskListUser и падает с
     * "detached entity passed to persist" ещё до раскладки по группам.
     */
    @Test
    @Transactional
    void findWithDueVisibleToUser_PrivateTaskOfOtherMember_HiddenPublicOfSameMemberVisible() {
        User requester = createUser("due-vis-req-" + UUID.randomUUID() + "@test.ru");
        User otherMember = createUser("due-vis-other-" + UUID.randomUUID() + "@test.ru");
        TaskList sharedList = createList(requester, "Общий список");
        addMember(sharedList, requester, TaskListRole.ADMIN);
        addMember(sharedList, otherMember, TaskListRole.USER);

        Todo otherPrivate = saveDueTodo(sharedList, otherMember, LocalDate.now(), true, false);
        Todo otherPublic = saveDueTodo(sharedList, otherMember, LocalDate.now(), false, false);

        List<Todo> visible = todoRepository.findWithDueVisibleToUser(requester.getId(), LocalDate.now().plusDays(7));

        assertThat(visible).extracting(Todo::getId)
                .contains(otherPublic.getId())
                .doesNotContain(otherPrivate.getId());
    }

    @Test
    @Transactional
    void findWithDueVisibleToUser_OwnPrivateTask_Visible() {
        User requester = createUser("due-vis-own-" + UUID.randomUUID() + "@test.ru");
        TaskList list = createList(requester, "Личное");
        addMember(list, requester, TaskListRole.ADMIN);

        Todo ownPrivate = saveDueTodo(list, requester, LocalDate.now(), true, false);

        List<Todo> visible = todoRepository.findWithDueVisibleToUser(requester.getId(), LocalDate.now().plusDays(7));

        assertThat(visible).extracting(Todo::getId).contains(ownPrivate.getId());
    }

    @Test
    @Transactional
    void findWithDueVisibleToUser_ListRequesterNotMemberOf_NotReturned() {
        User requester = createUser("due-vis-outsider-" + UUID.randomUUID() + "@test.ru");
        User stranger = createUser("due-vis-stranger-" + UUID.randomUUID() + "@test.ru");
        // Requester состоит в СВОЁМ списке — проверяем именно scoping по конкретному списку,
        // а не то, что у него вообще нет ни одного членства.
        TaskList ownList = createList(requester, "Свой список");
        addMember(ownList, requester, TaskListRole.ADMIN);

        TaskList foreignList = createList(stranger, "Чужой список");
        addMember(foreignList, stranger, TaskListRole.ADMIN);
        Todo foreignTodo = saveDueTodo(foreignList, stranger, LocalDate.now(), false, false);

        List<Todo> visible = todoRepository.findWithDueVisibleToUser(requester.getId(), LocalDate.now().plusDays(7));

        assertThat(visible).extracting(Todo::getId).doesNotContain(foreignTodo.getId());
    }

    @Test
    @Transactional
    void findWithDueVisibleToUser_NoDueDateOrDone_Excluded() {
        User requester = createUser("due-vis-excl-" + UUID.randomUUID() + "@test.ru");
        TaskList list = createList(requester, "Список");
        addMember(list, requester, TaskListRole.ADMIN);

        Todo noDueDate = saveDueTodoWithoutDueDate(list, requester);
        Todo doneWithDue = saveDueTodo(list, requester, LocalDate.now(), false, true);

        List<Todo> visible = todoRepository.findWithDueVisibleToUser(requester.getId(), LocalDate.now().plusDays(7));

        assertThat(visible).extracting(Todo::getId)
                .doesNotContain(noDueDate.getId(), doneWithDue.getId());
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

    /**
     * Создатель списка НЕ становится его участником автоматически (TaskList не каскадирует
     * task_list_user) — членство для видимости в findWithDueVisibleToUser нужно добавлять явно.
     */
    private void addMember(TaskList list, User user, TaskListRole role) {
        taskListUserRepository.save(new TaskListUser(list, user, role));
    }

    /** Задача со сроком для проверки видимости (приватность/принадлежность к списку). */
    private Todo saveDueTodo(TaskList list, User author, LocalDate dueDate, boolean isPrivate, boolean done) {
        Todo todo = new Todo();
        todo.setName("Задача со сроком видимости");
        todo.setDone(done);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(author);
        todo.setTaskList(list);
        todo.setDueDate(dueDate);
        todo.setIsPrivate(isPrivate);
        return todoRepository.saveAndFlush(todo);
    }

    /** Задача без срока в видимом списке — изолирует проверку "t.dueDate IS NOT NULL" от membership-фильтра. */
    private Todo saveDueTodoWithoutDueDate(TaskList list, User author) {
        Todo todo = new Todo();
        todo.setName("Без срока в видимом списке");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(author);
        todo.setTaskList(list);
        return todoRepository.saveAndFlush(todo);
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

    /** Задача со сроком для проверки выборки в findDueForReminder. */
    private Todo saveTodoWithDue(LocalDate dueDate, LocalTime dueTime, String timezone, int remindBeforeMinutes) {
        User user = createUser("due-query-" + UUID.randomUUID() + "@test.ru");
        TaskList list = createList(user, "Со сроком");
        Todo todo = new Todo();
        todo.setName("Задача со сроком");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(user);
        todo.setTaskList(list);
        todo.setDueDate(dueDate);
        todo.setDueTime(dueTime);
        todo.setDueTimezone(timezone);
        todo.setRemindBeforeMinutes(remindBeforeMinutes);
        return todoRepository.saveAndFlush(todo);
    }
}
