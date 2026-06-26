package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.dto.admin.SuggestionReseedReport;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end интеграционный тест ре-агрегации словаря (seed 029) на реальном Postgres.
 * <p>
 * Шифрование ВКЛЮЧЕНО (фиксированный ключ) — проверяет полный прод-путь:
 * расшифровка {@code todo.name} через converter → distinct-агрегация → запись авторов
 * РЕАЛЬНЫМ HMAC ({@link CryptoService#blindIndex}). Особо проверяет:
 * <ul>
 *   <li>{@code freq} = число РАЗНЫХ авторов (один автор ×N = 1), &lt;min-freq отбрасывается;</li>
 *   <li>{@code task_suggestion_user.user_hash} байт-в-байт == {@code blindIndex(normalized + ":" + userId)};</li>
 *   <li>инвариант {@code freq == COUNT(авторов)} для не-редакционных строк;</li>
 *   <li>приватные задачи не учитываются;</li>
 *   <li>occurrence-сид (не заблокированный) удаляется, заблокированный admin'ом — сохраняется;</li>
 *   <li>редакционные глаголы — floor freq=min-freq без авторов;</li>
 *   <li>dry-run ничего не пишет.</li>
 * </ul>
 */
@Tag("integration")
// Свой контекст с включённым шифрованием (app.encryption-key). CryptoServiceHolder держит
// CryptoService в СТАТИЧЕСКОМ поле — без изоляции этот ключ «протёк» бы в другие IT-классы
// (panel-review B-C1). DirtiesContext рвёт контекст после класса → следующий переинициализирует holder.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SuggestionReseedServiceIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        // 32-байтовый ключ → CryptoService включён (encrypt/decrypt + реальный HMAC blindIndex)
        registry.add("app.encryption-key", () -> Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    private SuggestionReseedService reseedService;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    @Autowired
    private TaskSuggestionRepository suggestionRepository;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long u1;
    private long u2;
    private long u3;
    private TaskList list;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM task_suggestion_user");
        jdbcTemplate.execute("DELETE FROM task_suggestion");
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();

        u1 = createUser("u1@reseed.ru").getId();
        u2 = createUser("u2@reseed.ru").getId();
        u3 = createUser("u3@reseed.ru").getId();
        list = taskListRepository.save(new TaskList("ReseedList", userRepository.findById(u1).orElseThrow()));
    }

    private User createUser(String email) {
        User u = new User();
        u.setAuthId(UUID.randomUUID().toString());
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode("pass"));
        u.setName(email);
        return userRepository.save(u);
    }

    private void todo(String name, long userId, boolean isPrivate) {
        Todo t = new Todo();
        t.setName(name); // converter зашифрует
        t.setCreatedAt(LocalDateTime.now());
        t.setDone(false);
        t.setIsPrivate(isPrivate);
        t.setUser(userRepository.findById(userId).orElseThrow());
        t.setTaskList(list);
        todoRepository.save(t);
    }

    private int authorCount(String text) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_suggestion_user WHERE text = ?", Integer.class, text);
    }

    @Test
    void reseed_EndToEnd_DistinctFreqHashAndCleanup() {
        // "молоко": 3 разных автора (разный регистр/пробелы нормализуются) → kept freq=3
        todo("Молоко", u1, false);
        todo("молоко", u2, false);
        todo("  молоко  ", u3, false);
        // "творог": 2 разных автора → ниже порога 3 → отбрасывается
        todo("творог", u1, false);
        todo("творог", u2, false);
        // "секрет": один автор ×3 → distinct=1 → отбрасывается (occurrence-дыра закрыта)
        todo("секрет", u1, false);
        todo("секрет", u1, false);
        todo("секрет", u1, false);
        // приватная задача — в словарь не идёт даже при 3 авторах
        todo("приват", u1, true);
        todo("приват", u2, true);
        todo("приват", u3, true);

        // occurrence-сид 026: не заблокированный (должен быть удалён) + заблокированный (сохранён)
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "старое", "старое", 50, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "плохое", "плохое", 99, true);

        SuggestionReseedReport report = reseedService.reseed(false);

        // --- молоко: freq=distinct=3, авторы = реальный HMAC, инвариант freq==COUNT(авторов) ---
        assertThat(suggestionRepository.findById("молоко").orElseThrow().getFreq()).isEqualTo(3);
        assertThat(authorCount("молоко")).isEqualTo(3);
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT user_hash FROM task_suggestion_user WHERE text = ?", String.class, "молоко");
        assertThat(hashes).containsExactlyInAnyOrder(
                cryptoService.blindIndex("молоко:" + u1),
                cryptoService.blindIndex("молоко:" + u2),
                cryptoService.blindIndex("молоко:" + u3));

        // --- ниже порога / один автор / приватные — отсутствуют ---
        assertThat(suggestionRepository.findById("творог")).isEmpty();
        assertThat(suggestionRepository.findById("секрет")).isEmpty();
        assertThat(suggestionRepository.findById("приват")).isEmpty();

        // --- occurrence-сид: не заблокированный удалён, заблокированный сохранён ---
        assertThat(suggestionRepository.findById("старое")).isEmpty();
        assertThat(suggestionRepository.findById("плохое")).isPresent();
        assertThat(suggestionRepository.findById("плохое").orElseThrow().isBlocked()).isTrue();
        assertThat(suggestionRepository.findById("плохое").orElseThrow().getFreq()).isEqualTo(99);

        // --- редакционные глаголы: floor freq=3 без авторов ---
        assertThat(suggestionRepository.findById("купить").orElseThrow().getFreq()).isEqualTo(3);
        assertThat(authorCount("купить")).isZero();

        // --- нет осиротевших строк-авторов (FK CASCADE отработал на delete) ---
        Integer orphans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_suggestion_user u " +
                        "WHERE NOT EXISTS (SELECT 1 FROM task_suggestion s WHERE s.text = u.text)",
                Integer.class);
        assertThat(orphans).isZero();

        // --- отчёт ---
        assertThat(report.isDryRun()).isFalse();
        assertThat(report.getProductsKept()).isEqualTo(1); // только молоко
        assertThat(report.getContributorRowsWritten()).isEqualTo(3);
        assertThat(report.getBlockedPreserved()).isEqualTo(1);
        assertThat(report.getNonBlockedDeleted()).isEqualTo(1); // "старое"
        assertThat(report.getEditorialVerbsFloored())
                .isEqualTo(SuggestionSeedTerms.EDITORIAL_TERMS.size());
        assertThat(report.getMinFreqApplied()).isEqualTo(3);
    }

    @Test
    void reseed_RunTwice_IsIdempotent() {
        todo("молоко", u1, false);
        todo("молоко", u2, false);
        todo("молоко", u3, false);

        reseedService.reseed(false);
        long freqAfterFirst = suggestionRepository.findById("молоко").orElseThrow().getFreq();
        int authorsAfterFirst = authorCount("молоко");

        // повторный прогон на тех же данных не должен ничего исказить (ON CONFLICT + полный rebuild)
        reseedService.reseed(false);

        assertThat(suggestionRepository.findById("молоко").orElseThrow().getFreq())
                .isEqualTo(freqAfterFirst).isEqualTo(3);
        assertThat(authorCount("молоко")).isEqualTo(authorsAfterFirst).isEqualTo(3); // не задвоилось
        assertThat(suggestionRepository.findById("купить").orElseThrow().getFreq()).isEqualTo(3);
    }

    @Test
    void reseed_DryRun_DoesNotChangeDatabase() {
        todo("молоко", u1, false);
        todo("молоко", u2, false);
        todo("молоко", u3, false);
        jdbcTemplate.update("INSERT INTO task_suggestion(text, text_display, freq, last_used_at, blocked) " +
                "VALUES (?, ?, ?, NOW(), ?)", "старое", "старое", 50, false);

        SuggestionReseedReport report = reseedService.reseed(true);

        // словарь не тронут: occurrence-сид на месте, молоко не записан, авторов нет
        assertThat(report.isDryRun()).isTrue();
        assertThat(report.getProductsKept()).isEqualTo(1);
        assertThat(report.getNonBlockedDeleted()).isEqualTo(1); // сколько БЫ удалили
        assertThat(suggestionRepository.findById("старое")).isPresent();
        assertThat(suggestionRepository.findById("молоко")).isEmpty();
        assertThat(authorCount("молоко")).isZero();
        // редакционные глаголы тоже не записаны при dry-run
        assertThat(suggestionRepository.findById("купить")).isEmpty();
    }
}
