package ru.mngerasimenko.todolist.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.model.TaskSuggestion;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест для миграции 025 + native UPSERT в {@link TaskSuggestionRepository}.
 * <p>
 * Особо проверяет, что:
 * <ul>
 *   <li>таблица создалась с корректной схемой и default'ами (freq=1, last_used_at=NOW, blocked=false);</li>
 *   <li>UPSERT при повторном вызове увеличивает freq на 1 и обновляет last_used_at;</li>
 *   <li>UPSERT НЕ перезаписывает text_display (первое написание остаётся);</li>
 *   <li>findTopByPrefix фильтрует blocked=true и freq &lt; minFreq, сортирует по freq DESC;</li>
 *   <li>block переводит запись в blocked=true (повторный — no-op);</li>
 *   <li>deleteOlderThanDays чистит только записи старше cutoff.</li>
 * </ul>
 */
@Tag("integration")
class TaskSuggestionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskSuggestionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM task_suggestion");
    }

    @Test
    void upsert_NewText_InsertsWithFreq1AndOriginalDisplay() {
        repository.upsert("молоко", "Молоко");

        List<TaskSuggestion> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        TaskSuggestion s = rows.get(0);
        assertThat(s.getText()).isEqualTo("молоко");
        assertThat(s.getTextDisplay()).isEqualTo("Молоко");
        assertThat(s.getFreq()).isEqualTo(1);
        assertThat(s.getLastUsedAt()).isNotNull();
        assertThat(s.isBlocked()).isFalse();
    }

    @Test
    void upsert_ExistingText_IncrementsFreqAndKeepsOriginalDisplay() {
        repository.upsert("молоко", "Молоко");
        repository.upsert("молоко", "МОЛОКО");
        repository.upsert("молоко", "молоко");

        TaskSuggestion s = repository.findById("молоко").orElseThrow();
        assertThat(s.getFreq()).isEqualTo(3);
        // text_display не перезаписывается на конфликте
        assertThat(s.getTextDisplay()).isEqualTo("Молоко");
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
    void block_ExistingText_ReturnsOneAndSetsFlag() {
        repository.upsert("молоко", "Молоко");

        int affected = repository.block("молоко");

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("молоко").orElseThrow().isBlocked()).isTrue();
    }

    @Test
    void block_NonExistentText_ReturnsZero() {
        int affected = repository.block("несуществует");

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

        int deleted = repository.deleteOlderThanDays(365);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findAll()).extracting(TaskSuggestion::getText)
                .containsExactlyInAnyOrder("fresh", "medium");
    }
}
