package ru.mngerasimenko.todolist.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration-тест ключа идемпотентности создания задачи (миграция 031).
 * <p>
 * Единственное место, где это вообще можно проверить: unit-тесты гоняются на H2 с выключенным
 * Liquibase и {@code ddl-auto=create-drop}, поэтому частичный уникальный индекс там не создаётся
 * в принципе — Hibernate его из аннотаций не выведет, а в JPA частичный индекс невыразим. Здесь
 * же поднимается настоящая PostgreSQL с реально накатанной миграцией.
 * <p>
 * Индекс проверяется явно, а не косвенно: он — единственная защита от гонки двух одновременных
 * ретраев, и если changeSet 031b по какой-то причине не применится, весь механизм тихо
 * выродится в SELECT-перед-INSERT, который эту гонку не ловит.
 */
@Tag("integration")
class TodoIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void migration031_CreatesPartialUniqueIndex() {
        @SuppressWarnings("unchecked")
        List<String> defs = entityManager.createNativeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_todo_user_client_request_id'")
                .getResultList();

        assertThat(defs).as("индекс миграции 031b не накатился").hasSize(1);
        String def = defs.get(0);
        assertThat(def).containsIgnoringCase("UNIQUE");
        assertThat(def).contains("user_id");
        assertThat(def).contains("client_request_id");
        // Частичность обязательна: без WHERE строки без ключа (старые клиенты, веб) конфликтовали
        // бы между собой по NULL и создание задачи сломалось бы для них полностью.
        assertThat(def).containsIgnoringCase("WHERE (client_request_id IS NOT NULL)");
    }

    @Test
    void sameKeyTwiceForSameUser_IsRejectedByIndex() {
        User user = createUser("idem1@test.ru");
        TaskList list = createList(user, "Покупки");
        String key = UUID.randomUUID().toString();

        todoRepository.saveAndFlush(newTodo(user, list, "лук репчатый", key));

        assertThatThrownBy(() -> todoRepository.saveAndFlush(newTodo(user, list, "лук репчатый", key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyForDifferentUsers_IsAllowed() {
        // Ключ генерирует клиент, глобальной уникальности между пользователями гарантировать
        // нельзя — область уникальности обязана быть автором, иначе чужой UUID ломал бы создание.
        User first = createUser("idem2@test.ru");
        User second = createUser("idem3@test.ru");
        String key = UUID.randomUUID().toString();

        todoRepository.saveAndFlush(newTodo(first, createList(first, "Свой"), "молоко", key));

        assertThatCode(() ->
                todoRepository.saveAndFlush(newTodo(second, createList(second, "Чужой"), "молоко", key)))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleTodosWithoutKey_DoNotConflict() {
        // Сборки до этой правки и веб-клиент ключ не шлют: у них client_request_id = NULL,
        // и такие строки обязаны спокойно сосуществовать.
        User user = createUser("idem4@test.ru");
        TaskList list = createList(user, "Покупки");

        todoRepository.saveAndFlush(newTodo(user, list, "молоко", null));

        assertThatCode(() -> todoRepository.saveAndFlush(newTodo(user, list, "молоко", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void findFirstByUserIdAndClientRequestId_FindsOwnKeyOnly() {
        // Derived-query на Todo, где userId есть и как скалярная read-only колонка, и как
        // ассоциация user. На H2 этот запрос не проверялся ни разу.
        User owner = createUser("idem5@test.ru");
        User stranger = createUser("idem6@test.ru");
        TaskList list = createList(owner, "Покупки");
        String key = UUID.randomUUID().toString();
        Todo saved = todoRepository.saveAndFlush(newTodo(owner, list, "лук репчатый", key));
        entityManager.clear();

        assertThat(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(owner.getId(), key))
                .get()
                .extracting(Todo::getId)
                .isEqualTo(saved.getId());
        assertThat(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(stranger.getId(), key))
                .isEmpty();
        assertThat(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(
                owner.getId(), UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void keySurvivesRoundTrip() {
        User user = createUser("idem7@test.ru");
        TaskList list = createList(user, "Покупки");
        String key = UUID.randomUUID().toString();

        Todo saved = todoRepository.saveAndFlush(newTodo(user, list, "лук репчатый", key));
        entityManager.clear();

        Todo loaded = todoRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getClientRequestId()).isEqualTo(key);
    }

    private Todo newTodo(User author, TaskList list, String name, String clientRequestId) {
        Todo todo = new Todo();
        todo.setName(name);
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setUser(author);
        todo.setTaskList(list);
        todo.setClientRequestId(clientRequestId);
        return todo;
    }

    private User createUser(String email) {
        User user = new User();
        user.setAuthId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setEmailHash(email); // без шифрования в тесте: hash = email, как в UserRepositoryTest
        user.setPassword("password123");
        user.setName("Idempotency Test User");
        return userRepository.save(user);
    }

    private TaskList createList(User owner, String name) {
        return taskListRepository.save(new TaskList(name, owner));
    }
}
