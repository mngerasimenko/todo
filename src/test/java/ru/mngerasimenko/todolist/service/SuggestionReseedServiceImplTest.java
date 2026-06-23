package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.dto.admin.SuggestionReseedReport;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link SuggestionReseedServiceImpl} (seed 029): distinct-агрегация, порог,
 * editorial-floor, сохранение blocked, dry-run без записи, корректность хеша автора.
 * <p>
 * Используется реальный {@link SuggestionTextFilter} на моках blacklist+properties — фильтры
 * reseed обязаны быть идентичны live-трекингу.
 */
@ExtendWith(MockitoExtension.class)
class SuggestionReseedServiceImplTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TaskSuggestionRepository suggestionRepository;

    @Mock
    private BlacklistService blacklist;

    @Mock
    private CryptoService cryptoService;

    @Mock
    private CacheManager cacheManager;

    private SuggestionReseedServiceImpl service;
    private SuggestionProperties properties;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        properties = new SuggestionProperties(); // minFreq=3 по дефолту
        SuggestionTextFilter filter = new SuggestionTextFilter(blacklist, properties);
        service = new SuggestionReseedServiceImpl(
                todoRepository, suggestionRepository, filter, properties, cryptoService, cacheManager);
        lenient().when(blacklist.contains(anyString())).thenReturn(false);
        lenient().when(cryptoService.blindIndex(anyString()))
                .thenAnswer(inv -> "h:" + inv.getArgument(0));
        lenient().when(suggestionRepository.findBlockedTexts()).thenReturn(List.of());
        // advisory-lock берётся успешно (нет конкурентного reseed) — иначе unstubbed boolean=false → 409
        lenient().when(suggestionRepository.tryReseedAdvisoryLock(anyLong())).thenReturn(true);
    }

    private Todo todo(String name, long userId) {
        Todo t = new Todo();
        t.setId(nextId++); // keyset-пагинация двигает курсор по id — id обязателен
        t.setName(name);
        t.setUserId(userId);
        t.setIsPrivate(false);
        return t;
    }

    private void givenTodos(Todo... todos) {
        // keyset: первый батч (afterId=0) — все задачи; размер < PAGE_SIZE → цикл останавливается.
        when(todoRepository.findNonPrivateForReseed(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(todos));
    }

    // ===== порог distinct =====

    @Test
    void reseed_KeepsAtThresholdThree_DropsBelow() {
        // "молоко": 3 разных автора → kept; "сок": 2 → ниже порога, не пишем.
        givenTodos(
                todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L),
                todo("сок", 1L), todo("сок", 2L)
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository).insertReseed("молоко", "молоко", 3L);
        verify(suggestionRepository, never()).insertReseed(eq("сок"), anyString(), anyLong());
        assertThat(report.getProductsKept()).isEqualTo(1);
        assertThat(report.getDistinctProductsTotal()).isEqualTo(2);
    }

    @Test
    void reseed_FreqEqualsDistinctAuthors_NotOccurrences() {
        // один автор вводит "хлеб" трижды + двое других по разу → distinct=3, НЕ 5 вхождений.
        givenTodos(
                todo("хлеб", 1L), todo("хлеб", 1L), todo("хлеб", 1L),
                todo("хлеб", 2L), todo("хлеб", 3L)
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository).insertReseed("хлеб", "хлеб", 3L); // distinct=3, не 5
        assertThat(report.getContributorRowsWritten()).isEqualTo(3);
        // ровно по одной строке-автору на каждого distinct-пользователя
        verify(suggestionRepository).addSuggestionUser("хлеб", "h:хлеб:1");
        verify(suggestionRepository).addSuggestionUser("хлеб", "h:хлеб:2");
        verify(suggestionRepository).addSuggestionUser("хлеб", "h:хлеб:3");
    }

    @Test
    void reseed_SingleUserManyTimes_BelowThreshold_Dropped() {
        // один автор × 10 → distinct=1 → не всплывает (закрывает occurrence-дыру).
        givenTodos(
                todo("секрет", 7L), todo("секрет", 7L), todo("секрет", 7L),
                todo("секрет", 7L), todo("секрет", 7L)
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository, never()).insertReseed(eq("секрет"), anyString(), anyLong());
        assertThat(report.getProductsKept()).isZero();
    }

    // ===== хеш автора =====

    @Test
    void reseed_AuthorStoredAsBlindIndexOfNormalizedAndUserId() {
        givenTodos(todo("Кефир", 1L), todo("кефир", 2L), todo("  кефир  ", 3L));

        service.reseed(false);

        // нормализация (lower+trim) объединяет варианты → один ключ "кефир", 3 автора
        verify(suggestionRepository).insertReseed("кефир", "кефир", 3L);
        verify(suggestionRepository).addSuggestionUser("кефир", "h:кефир:1");
        verify(suggestionRepository).addSuggestionUser("кефир", "h:кефир:2");
        verify(suggestionRepository).addSuggestionUser("кефир", "h:кефир:3");
    }

    // ===== editorial verbs floor =====

    @Test
    void reseed_EditorialVerbNotInData_FlooredWithoutAuthors() {
        givenTodos(todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L));

        SuggestionReseedReport report = service.reseed(false);

        // "купить" — редакционный глагол, в данных нет → floor freq=3, без строк-авторов
        verify(suggestionRepository).insertReseed("купить", "купить", 3L);
        verify(suggestionRepository, never()).addSuggestionUser(eq("купить"), anyString());
        assertThat(report.getEditorialVerbsFloored())
                .isEqualTo(SuggestionSeedVerbs.EDITORIAL_VERBS.size());
    }

    @Test
    void reseed_EditorialVerbInRealData_RealWins_NotFloored() {
        // "позвонить" всплыл из реальных данных с 3 авторами → реальная агрегация, не floor.
        givenTodos(
                todo("позвонить", 1L), todo("позвонить", 2L), todo("позвонить", 3L)
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository).insertReseed("позвонить", "позвонить", 3L);
        verify(suggestionRepository).addSuggestionUser("позвонить", "h:позвонить:1"); // есть авторы
        // floor-набор на 1 меньше полного (позвонить ушёл в реальные)
        assertThat(report.getEditorialVerbsFloored())
                .isEqualTo(SuggestionSeedVerbs.EDITORIAL_VERBS.size() - 1);
    }

    // ===== blocked сохраняется =====

    @Test
    void reseed_BlockedText_NotRewritten() {
        when(suggestionRepository.findBlockedTexts()).thenReturn(List.of("плохое"));
        givenTodos(
                todo("плохое", 1L), todo("плохое", 2L), todo("плохое", 3L), // ≥3, но blocked
                todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L)
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository, never()).insertReseed(eq("плохое"), anyString(), anyLong());
        verify(suggestionRepository, never()).addSuggestionUser(eq("плохое"), anyString());
        verify(suggestionRepository).insertReseed("молоко", "молоко", 3L);
        assertThat(report.getBlockedPreserved()).isEqualTo(1);
    }

    @Test
    void reseed_BlockedEditorialVerb_NotFloored() {
        when(suggestionRepository.findBlockedTexts()).thenReturn(List.of("купить"));
        givenTodos(todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L));

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository, never()).insertReseed(eq("купить"), anyString(), anyLong());
        assertThat(report.getEditorialVerbsFloored())
                .isEqualTo(SuggestionSeedVerbs.EDITORIAL_VERBS.size() - 1);
    }

    // ===== фильтры применяются (как у live-track) =====

    @Test
    void reseed_AppliesTrackFilters_SkipsEmailDigitsShort() {
        givenTodos(
                todo("user@mail.ru", 1L), todo("user@mail.ru", 2L), todo("user@mail.ru", 3L), // email
                todo("дом 12", 1L), todo("дом 12", 2L), todo("дом 12", 3L),                    // 2 цифры
                todo("ок", 1L), todo("ок", 2L), todo("ок", 3L),                                // короче 3
                todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L)                     // ок
        );

        SuggestionReseedReport report = service.reseed(false);

        verify(suggestionRepository).insertReseed("молоко", "молоко", 3L);
        verify(suggestionRepository, never()).insertReseed(eq("user@mail.ru"), anyString(), anyLong());
        verify(suggestionRepository, never()).insertReseed(eq("дом 12"), anyString(), anyLong());
        verify(suggestionRepository, never()).insertReseed(eq("ок"), anyString(), anyLong());
        assertThat(report.getTodosScanned()).isEqualTo(12);
        assertThat(report.getTodosTrackable()).isEqualTo(3); // только 3 «молоко» прошли
    }

    // ===== dry-run =====

    @Test
    void reseed_DryRun_ComputesButDoesNotWrite() {
        givenTodos(
                todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L),
                todo("сок", 1L), todo("сок", 2L)
        );
        when(suggestionRepository.countNonBlocked()).thenReturn(40L);

        SuggestionReseedReport report = service.reseed(true);

        // ничего не пишем
        verify(suggestionRepository, never()).deleteAllNonBlocked();
        verify(suggestionRepository, never()).insertReseed(anyString(), anyString(), anyLong());
        verify(suggestionRepository, never()).addSuggestionUser(anyString(), anyString());
        // но отчёт посчитан
        assertThat(report.isDryRun()).isTrue();
        assertThat(report.getProductsKept()).isEqualTo(1); // молоко
        assertThat(report.getNonBlockedDeleted()).isEqualTo(40L); // сколько БЫ удалили
        assertThat(report.getEditorialVerbsFloored())
                .isEqualTo(SuggestionSeedVerbs.EDITORIAL_VERBS.size());
    }

    @Test
    void reseed_Apply_DeletesNonBlockedBeforeRebuild() {
        givenTodos(todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L));

        service.reseed(false);

        verify(suggestionRepository).deleteAllNonBlocked();
    }

    // ===== keyset-пагинация через несколько страниц =====

    @Test
    void reseed_KeysetPaginatesPastFullPage_CountsAllAuthors() {
        // Первая страница ровно PAGE_SIZE(500) → цикл обязан запросить вторую (keyset по id).
        List<Todo> page1 = new ArrayList<>();
        for (long u = 1; u <= 500; u++) {
            page1.add(todo("молоко", u)); // id 1..500, авторы 1..500
        }
        Todo page2 = todo("молоко", 501L); // id 501, 501-й автор — только если 2-ю страницу прочитали
        when(todoRepository.findNonPrivateForReseed(eq(0L), any(Pageable.class))).thenReturn(page1);
        when(todoRepository.findNonPrivateForReseed(eq(500L), any(Pageable.class))).thenReturn(List.of(page2));

        service.reseed(false);

        // 501 distinct-автор доказывает, что вторая страница прочитана (иначе было бы 500)
        verify(suggestionRepository).insertReseed("молоко", "молоко", 501L);
    }

    @Test
    void reseed_AdvisoryLockHeld_Returns409() {
        when(suggestionRepository.tryReseedAdvisoryLock(anyLong())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reseed(false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        // при занятом локе ничего не пишем
        verify(suggestionRepository, never()).deleteAllNonBlocked();
    }

    @Test
    void reseed_DryRun_DoesNotTakeAdvisoryLock() {
        givenTodos(todo("молоко", 1L), todo("молоко", 2L), todo("молоко", 3L));

        service.reseed(true);

        verify(suggestionRepository, never()).tryReseedAdvisoryLock(anyLong());
    }
}
