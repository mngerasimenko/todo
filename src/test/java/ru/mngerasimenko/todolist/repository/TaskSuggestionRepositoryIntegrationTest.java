package ru.mngerasimenko.todolist.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.TaskSuggestion;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест для миграций 025/028 + distinct-учёта в {@link TaskSuggestionRepository}.
 * <p>
 * {@code @Modifying}-методы репозитория требуют транзакцию (в проде её даёт вызывающий —
 * track {@code REQUIRES_NEW} / scheduler / service.block). Здесь каждую запись оборачиваем в
 * {@link TransactionTemplate} (своя коммит-транзакция на операцию, как REQUIRES_NEW сервиса),
 * а чтения (findById/findTopByPrefix self-transactional, jdbcTemplate auto-commit) идут после
 * коммита → всегда свежие, без stale L1-cache.
 * <p>
 * Автор хранится псевдонимом (hash) — здесь используются синтетические хеши ("u1"/"u2"):
 * репозиторий проверяет SQL-механику distinct (одинаковый хеш = тот же автор), само HMAC —
 * на уровне сервиса. Особо проверяет, что:
 * <ul>
 *   <li>ensureSuggestion создаёт строку с freq=0 и НЕ перезаписывает text_display на конфликте;</li>
 *   <li>addSuggestionUser возвращает 1 для нового автора и 0 для повторного (ON CONFLICT DO NOTHING);</li>
 *   <li>distinct-flow даёт freq = число РАЗНЫХ авторов = COUNT строк-авторов;</li>
 *   <li>порог: 3 разных автора всплывают на minFreq=3, а 2 — нет (end-to-end gate);</li>
 *   <li>findTopByPrefix фильтрует blocked=true и freq &lt; minFreq, сортирует по freq DESC;</li>
 *   <li>block переводит запись в blocked=true (повторный — no-op);</li>
 *   <li>deleteOlderThanDays чистит только старые записи И каскадит в task_suggestion_user (FK).</li>
 * </ul>
 */
@Tag("integration")
class TaskSuggestionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskSuggestionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanup() {
        tx = new TransactionTemplate(txManager);
        // FK task_suggestion_user → task_suggestion(text) ON DELETE CASCADE: удаление родителя
        // чистит и дочерние строки, но чистим явно для независимости от порядка.
        jdbcTemplate.execute("DELETE FROM task_suggestion_user");
        jdbcTemplate.execute("DELETE FROM task_suggestion");
    }

    /** Выполнить запись в собственной коммит-транзакции (как REQUIRES_NEW сервиса). */
    private void inTx(Runnable r) {
        tx.executeWithoutResult(status -> r.run());
    }

    /** То же, но с возвратом значения (для @Modifying-методов, отдающих rows-affected). */
    private <T> T inTxGet(Supplier<T> s) {
        return tx.execute(status -> s.get());
    }

    /** Повторяет distinct-flow сервиса в одной транзакции: ensure → addUser → (если новый) increment. */
    private void trackOnce(String text, String display, String userHash) {
        inTx(() -> {
            repository.ensureSuggestion(text, display);
            if (repository.addSuggestionUser(text, userHash) > 0) {
                repository.incrementFreq(text);
            }
        });
    }

    private long freqOf(String text) {
        return repository.findById(text).orElseThrow().getFreq();
    }

    private int authorCountOf(String text) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_suggestion_user WHERE text = ?", Integer.class, text);
    }

    @Test
    void ensureSuggestion_NewText_InsertsWithFreq0AndOriginalDisplay() {
        inTx(() -> repository.ensureSuggestion("молоко", "Молоко"));

        List<TaskSuggestion> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        TaskSuggestion s = rows.get(0);
        assertThat(s.getText()).isEqualTo("молоко");
        assertThat(s.getTextDisplay()).isEqualTo("Молоко");
        assertThat(s.getFreq()).isZero(); // ещё ни одного distinct-автора
        assertThat(s.getLastUsedAt()).isNotNull();
        assertThat(s.isBlocked()).isFalse();
    }

    @Test
    void ensureSuggestion_ExistingText_KeepsFreqAndOriginalDisplay() {
        inTx(() -> {
            repository.ensureSuggestion("молоко", "Молоко");
            repository.incrementFreq("молоко"); // freq=1
        });
        inTx(() -> repository.ensureSuggestion("молоко", "МОЛОКО")); // повтор — не трогает freq и display

        TaskSuggestion s = repository.findById("молоко").orElseThrow();
        assertThat(s.getFreq()).isEqualTo(1);
        assertThat(s.getTextDisplay()).isEqualTo("Молоко");
    }

    @Test
    void addSuggestionUser_NewAuthorReturnsOne_DuplicateReturnsZero() {
        inTx(() -> repository.ensureSuggestion("молоко", "Молоко"));

        assertThat(inTxGet(() -> repository.addSuggestionUser("молоко", "u1"))).isEqualTo(1); // новый автор
        assertThat(inTxGet(() -> repository.addSuggestionUser("молоко", "u1"))).isZero();      // тот же — дубль
        assertThat(inTxGet(() -> repository.addSuggestionUser("молоко", "u2"))).isEqualTo(1); // другой автор
    }

    @Test
    void distinctFlow_FreqEqualsDistinctAuthorCount_NotOccurrences() {
        trackOnce("молоко", "Молоко", "u1");
        trackOnce("молоко", "Молоко", "u1"); // тот же автор повторно — freq не растёт
        assertThat(freqOf("молоко")).isEqualTo(1);
        assertThat(authorCountOf("молоко")).isEqualTo(1); // freq == число строк-авторов

        trackOnce("молоко", "Молоко", "u2");
        assertThat(freqOf("молоко")).isEqualTo(2);

        trackOnce("молоко", "Молоко", "u2"); // повтор — без изменений
        trackOnce("молоко", "Молоко", "u3");
        assertThat(freqOf("молоко")).isEqualTo(3);
        assertThat(authorCountOf("молоко")).isEqualTo(3); // инвариант держится

        // text_display сохраняется от первого вызова
        assertThat(repository.findById("молоко").orElseThrow().getTextDisplay()).isEqualTo("Молоко");
    }

    @Test
    void gate_ThreeDistinctAuthors_SurfaceAtThreshold3_NotAt2() {
        // end-to-end доказательство юр-условия: строку видно ТОЛЬКО при >=3 разных авторах
        trackOnce("кефир", "кефир", "u1");
        trackOnce("кефир", "кефир", "u1"); // тот же автор дважды — всё ещё 1 distinct
        trackOnce("кефир", "кефир", "u2");
        assertThat(repository.findTopByPrefix("кеф%", 3L, PageRequest.of(0, 5)))
                .as("2 разных автора < порога 3 — не всплывает").isEmpty();

        trackOnce("кефир", "кефир", "u3"); // 3-й distinct-автор
        assertThat(repository.findTopByPrefix("кеф%", 3L, PageRequest.of(0, 5)))
                .extracting(TaskSuggestion::getText).containsExactly("кефир");
        assertThat(freqOf("кефир")).isEqualTo(3);
        assertThat(authorCountOf("кефир")).isEqualTo(3);
    }

    @Test
    void findTopByPrefix_FiltersFreqBelowMinAndBlocked() {
        // freq=3 — в выдаче, freq=2 — отрезаем порогом minFreq=3, blocked=true — скрыт
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "хлеб", "Хлеб", 5, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "хлебушек", "Хлебушек", 3, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "хлопья", "Хлопья", 2, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "хлеб ржаной", "Хлеб ржаной", 4, true);

        List<TaskSuggestion> rows = repository.findTopByPrefix("хл%", 3L, PageRequest.of(0, 5));

        assertThat(rows).extracting(TaskSuggestion::getText)
                .containsExactly("хлеб", "хлебушек");
    }

    @Test
    void findTopByPrefix_OrdersByFreqDesc() {
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "м1", "м1", 5, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "м2", "м2", 10, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "м3", "м3", 7, false);

        List<TaskSuggestion> rows = repository.findTopByPrefix("м%", 1, PageRequest.of(0, 5));

        assertThat(rows).extracting(TaskSuggestion::getText)
                .containsExactly("м2", "м3", "м1");
    }

    @Test
    void findTopByPrefix_RespectsLimitFromPageable() {
        for (int i = 0; i < 20; i++) {
            jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                    "VALUES (?, ?, ?, NOW(), ?)", "x" + i, "x" + i, 10 + i, false);
        }

        List<TaskSuggestion> rows = repository.findTopByPrefix("x%", 1, PageRequest.of(0, 5));

        assertThat(rows).hasSize(5);
    }

    @Test
    void findAllVisible_FiltersBlockedAndBelowMinFreq_OrdersByFreqDesc() {
        // Bulk-выгрузка (Server R-7): freq>=3 И blocked=false попадают; freq<3 и blocked отрезаются.
        // Частоты различны → порядок задаётся freq DESC независимо от collation тай-брейка по text.
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "молоко", "Молоко", 9, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "масло", "Масло", 5, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "мясо", "Мясо", 3, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "редкое", "редкое", 2, false); // < minFreq=3 → отрезается
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "мат", "мат", 7, true);        // blocked → скрыт

        List<TaskSuggestion> rows = repository.findAllVisible(3L);

        assertThat(rows).extracting(TaskSuggestion::getText)
                .containsExactly("молоко", "масло", "мясо");
        // text_display и freq переносятся как есть (для локального ранжирования на клиенте)
        assertThat(rows.get(0).getTextDisplay()).isEqualTo("Молоко");
        assertThat(rows.get(0).getFreq()).isEqualTo(9);
    }

    @Test
    void findAllVisible_DeterministicOrderForStableETag() {
        // При равной частоте порядок добивается text ASC (PK уникален) → выдача стабильна между
        // вызовами при неизменных данных. Это и есть гарантия стабильного ETag на контроллере.
        // Вставляем намеренно вперемешку, чтобы проверить именно тай-брейк, а не физический порядок.
        for (int i : new int[]{3, 0, 4, 1, 2}) {
            jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                    "VALUES (?, ?, ?, NOW(), ?)", "p" + i, "p" + i, 3, false);
        }

        List<TaskSuggestion> rows = repository.findAllVisible(3L);

        // Точный порядок (а не просто «два вызова совпали»): тай-брейк text ASC обязателен —
        // без него ETag мог бы плыть. ASCII-ключи p0..p4 не зависят от collation.
        assertThat(rows).extracting(TaskSuggestion::getText)
                .containsExactly("p0", "p1", "p2", "p3", "p4");
    }

    @Test
    void block_ExistingText_ReturnsOneAndSetsFlag() {
        inTx(() -> repository.ensureSuggestion("молоко", "Молоко"));

        int affected = inTxGet(() -> repository.block("молоко"));

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("молоко").orElseThrow().isBlocked()).isTrue();
    }

    @Test
    void block_NonExistentText_ReturnsZero() {
        int affected = inTxGet(() -> repository.block("несуществует"));

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void deleteOlderThanDays_RemovesOnlyOldRecords() {
        // Свежая (не должна удаляться)
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, ?, ?)", "fresh", "fresh", 1, LocalDateTime.now(), false);
        // 100 дней назад (не должна удаляться при cutoff=365)
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, ?, ?)", "medium", "medium", 1, LocalDateTime.now().minusDays(100), false);
        // 400 дней назад (должна удаляться)
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, ?, ?)", "old", "old", 1, LocalDateTime.now().minusDays(400), false);

        int deleted = inTxGet(() -> repository.deleteOlderThanDays(365));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findAll()).extracting(TaskSuggestion::getText)
                .containsExactlyInAnyOrder("fresh", "medium");
    }

    @Test
    void deleteOlderThanDays_CascadesToTaskSuggestionUser_ButKeepsFreshChildren() {
        // строка-старьё с авторами, которую удалит cleanup
        trackOnce("старьё", "старьё", "u1");
        inTx(() -> repository.addSuggestionUser("старьё", "u2"));
        jdbcTemplate.update("UPDATE task_suggestion SET last_used_at = ? WHERE text = ?",
                LocalDateTime.now().minusDays(400), "старьё");
        // свежая строка с автором — НЕ должна пострадать (CASCADE точечный, не глобальный)
        trackOnce("свежак", "свежак", "u1");

        inTx(() -> repository.deleteOlderThanDays(365));

        assertThat(authorCountOf("старьё")).isZero();  // FK ON DELETE CASCADE убрал авторов
        assertThat(authorCountOf("свежак")).isEqualTo(1); // чужие авторы целы
    }
}
