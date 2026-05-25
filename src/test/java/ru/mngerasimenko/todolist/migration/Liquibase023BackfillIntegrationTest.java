package ru.mngerasimenko.todolist.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test для backfill SQL миграции 023 (changeSets 023b и 023c).
 * Эти changeSets — `dbms: postgresql`, поэтому unit-тесты на H2 их пропускают.
 * Тест выполняется на реальной PostgreSQL через TestContainers.
 *
 * Подход: Liquibase 023 уже применён на чистой БД при старте контейнера (backfill
 * no-op на пустых таблицах). Тест вставляет данные с position=0 напрямую через
 * JdbcTemplate, затем вручную выполняет тот же UPDATE-statement что в 023b/023c
 * и проверяет результат. Это валидирует SQL-семантику без зависимости от
 * перезапуска Liquibase.
 *
 * Проверяемое поведение:
 *  1) 023b: task_list_user.position = ROW_NUMBER() PARTITION BY user_id ORDER BY joined_at - 1
 *  2) 023c: todo.position = ROW_NUMBER() PARTITION BY list_id ORDER BY id - 1
 *  3) Идемпотентность: position != 0 не перезаписывается (guard `WHERE position = 0`)
 */
@Tag("integration")
class Liquibase023BackfillIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        // FK-safe порядок: сначала зависимые, потом владельцы.
        // refresh_token, push_token, invite_token имеют ON DELETE CASCADE,
        // но удаляем явно для предсказуемости (на случай если что-то осталось
        // от других интеграционных тестов в той же JVM).
        jdbcTemplate.execute("DELETE FROM todo");
        jdbcTemplate.execute("DELETE FROM task_list_user");
        jdbcTemplate.execute("DELETE FROM invite_token");
        jdbcTemplate.execute("DELETE FROM task_list");
        jdbcTemplate.execute("DELETE FROM refresh_token");
        jdbcTemplate.execute("DELETE FROM push_token");
        // Сохраняем системного юзера id=0 ("Удалённый пользователь" из миграции 008),
        // на случай если он где-то используется FK'ами в продовых тестах.
        jdbcTemplate.execute("DELETE FROM todo_users WHERE id != 0");
    }

    // === Тест 1: 023b backfill — позиция по joined_at ASC внутри user_id ===

    @Test
    void backfill_taskListUser_position_setsByJoinedAtAscPerUser() {
        // GIVEN: 1 юзер, 2 списка, 2 task_list_user строки с разным joined_at и position=0
        Long userId = insertUser();
        Long listAId = insertList(userId, "List A");
        Long listBId = insertList(userId, "List B");
        insertTaskListUser(listAId, userId, "ADMIN", LocalDateTime.of(2026, 1, 1, 0, 0), 0);
        insertTaskListUser(listBId, userId, "ADMIN", LocalDateTime.of(2026, 1, 2, 0, 0), 0);

        // WHEN: выполняем backfill SQL (тот же UPDATE что в 023b)
        executeBackfillTaskListUserPosition();

        // THEN: list A (более ранний joined_at) → position 0, list B → position 1
        assertThat(getPosition(listAId, userId)).isEqualTo(0);
        assertThat(getPosition(listBId, userId)).isEqualTo(1);
    }

    // === Тест 2: 023b backfill — idempotence guard ===

    @Test
    void backfill_taskListUser_idempotenceGuard_doesNotOverwriteNonZero() {
        // GIVEN: строка уже имеет position=5 (юзер уже переупорядочил списки)
        Long userId = insertUser();
        Long listId = insertList(userId, "L");
        insertTaskListUser(listId, userId, "ADMIN", LocalDateTime.now(), 5);

        // WHEN: повторный backfill
        executeBackfillTaskListUserPosition();

        // THEN: position остался 5
        assertThat(getPosition(listId, userId)).isEqualTo(5);
    }

    // === Тест 3: 023c backfill — позиция по id ASC внутри list_id ===

    @Test
    void backfill_todo_position_setsByIdAscPerList() {
        Long userId = insertUser();
        Long listId = insertList(userId, "L");
        insertTaskListUser(listId, userId, "ADMIN", LocalDateTime.now(), 0);
        Long todoAId = insertTodo(listId, userId, "A", 0);
        Long todoBId = insertTodo(listId, userId, "B", 0);
        Long todoCId = insertTodo(listId, userId, "C", 0);

        executeBackfillTodoPosition();

        // меньший id → меньшая позиция
        assertThat(getTodoPosition(todoAId)).isEqualTo(0);
        assertThat(getTodoPosition(todoBId)).isEqualTo(1);
        assertThat(getTodoPosition(todoCId)).isEqualTo(2);
    }

    // === Тест 4: 023c backfill — idempotence guard ===

    @Test
    void backfill_todo_idempotenceGuard_doesNotOverwriteNonZero() {
        Long userId = insertUser();
        Long listId = insertList(userId, "L");
        insertTaskListUser(listId, userId, "ADMIN", LocalDateTime.now(), 0);
        Long todoId = insertTodo(listId, userId, "T", 7);

        executeBackfillTodoPosition();

        assertThat(getTodoPosition(todoId)).isEqualTo(7);
    }

    // === Тест 5: 024b backfill — per-user color наследуется из общего task_list.color ===

    @Test
    void backfill_taskListUser_color_inheritsFromTaskList() {
        Long userId = insertUser();
        Long listId = insertList(userId, "L");
        setListColor(listId, "#EA4335");                                  // общий цвет на task_list
        insertTaskListUser(listId, userId, "ADMIN", LocalDateTime.now(), 0); // per-user color = NULL

        executeBackfillTaskListUserColor();

        assertThat(getColor(listId, userId)).isEqualTo("#EA4335");
    }

    // === Тест 6: 024b backfill — idempotence guard (не перезаписывает заданный per-user цвет) ===

    @Test
    void backfill_taskListUser_color_idempotenceGuard_doesNotOverwriteNonNull() {
        Long userId = insertUser();
        Long listId = insertList(userId, "L");
        setListColor(listId, "#EA4335");
        insertTaskListUserWithColor(listId, userId, "ADMIN", "#000000"); // уже свой цвет

        executeBackfillTaskListUserColor();

        assertThat(getColor(listId, userId)).isEqualTo("#000000"); // не тронут
    }

    // === Хелперы для вставки данных ===

    /**
     * Создаёт минимально-валидного юзера. Все NOT NULL поля заполнены тестовыми значениями.
     * Email и email_hash — уникальные через nanoTime, что исключает коллизии между тестами.
     * Поля email/name пишутся в plaintext напрямую через JDBC (минуя JPA-converter с шифрованием),
     * email_hash — это plaintext-email для теста (в проде там HMAC-SHA256).
     */
    private Long insertUser() {
        long ts = System.nanoTime();
        String authId = "auth-" + ts;
        String email = "test-" + ts + "@example.com";
        return jdbcTemplate.queryForObject(
                "INSERT INTO todo_users (auth_id, email, email_hash, password, name, email_verified, created_at) " +
                        "VALUES (?, ?, ?, 'hash', ?, true, NOW()) RETURNING id",
                Long.class, authId, email, email, "Test-" + ts);
    }

    private Long insertList(Long creatorId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO task_list (name, creator_id, created_at) VALUES (?, ?, NOW()) RETURNING id",
                Long.class, name, creatorId);
    }

    private void insertTaskListUser(Long listId, Long userId, String role, LocalDateTime joinedAt, int position) {
        jdbcTemplate.update(
                "INSERT INTO task_list_user (list_id, user_id, role, joined_at, position) " +
                        "VALUES (?, ?, ?, ?, ?)",
                listId, userId, role, joinedAt, position);
    }

    private Long insertTodo(Long listId, Long userId, String name, int position) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO todo (list_id, user_id, name, done, created_at, position) " +
                        "VALUES (?, ?, ?, false, NOW(), ?) RETURNING id",
                Long.class, listId, userId, name, position);
    }

    private Integer getPosition(Long listId, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT position FROM task_list_user WHERE list_id = ? AND user_id = ?",
                Integer.class, listId, userId);
    }

    private Integer getTodoPosition(Long todoId) {
        return jdbcTemplate.queryForObject("SELECT position FROM todo WHERE id = ?", Integer.class, todoId);
    }

    private void setListColor(Long listId, String color) {
        jdbcTemplate.update("UPDATE task_list SET color = ? WHERE id = ?", color, listId);
    }

    private void insertTaskListUserWithColor(Long listId, Long userId, String role, String color) {
        jdbcTemplate.update(
                "INSERT INTO task_list_user (list_id, user_id, role, joined_at, position, color) " +
                        "VALUES (?, ?, ?, ?, 0, ?)",
                listId, userId, role, LocalDateTime.now(), color);
    }

    private String getColor(Long listId, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT color FROM task_list_user WHERE list_id = ? AND user_id = ?",
                String.class, listId, userId);
    }

    /**
     * Повторяет UPDATE из changeSet 024b (один-в-один SQL).
     */
    private void executeBackfillTaskListUserColor() {
        jdbcTemplate.execute(
                "UPDATE task_list_user tlu " +
                        "SET color = tl.color " +
                        "FROM task_list tl " +
                        "WHERE tlu.list_id = tl.id " +
                        "  AND tl.color IS NOT NULL " +
                        "  AND tlu.color IS NULL"
        );
    }

    /**
     * Повторяет UPDATE из changeSet 023b (один-в-один SQL).
     * Любое расхождение между этим SQL и YAML — баг теста.
     */
    private void executeBackfillTaskListUserPosition() {
        jdbcTemplate.execute(
                "UPDATE task_list_user tlu " +
                        "SET position = sub.rn - 1 " +
                        "FROM ( " +
                        "  SELECT list_id, user_id, " +
                        "         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY joined_at) AS rn " +
                        "  FROM task_list_user " +
                        ") sub " +
                        "WHERE tlu.list_id = sub.list_id " +
                        "  AND tlu.user_id = sub.user_id " +
                        "  AND tlu.position = 0"
        );
    }

    /**
     * Повторяет UPDATE из changeSet 023c (один-в-один SQL).
     */
    private void executeBackfillTodoPosition() {
        jdbcTemplate.execute(
                "UPDATE todo t " +
                        "SET position = sub.rn - 1 " +
                        "FROM ( " +
                        "  SELECT id, ROW_NUMBER() OVER (PARTITION BY list_id ORDER BY id) AS rn " +
                        "  FROM todo " +
                        ") sub " +
                        "WHERE t.id = sub.id AND t.position = 0"
        );
    }
}
