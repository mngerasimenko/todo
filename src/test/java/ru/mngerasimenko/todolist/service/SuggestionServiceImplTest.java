package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.TaskSuggestion;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link SuggestionServiceImpl}: проверка цепочки фильтров {@code track},
 * корректности {@code suggest} с нормализацией prefix и limit-clamping, и идемпотентности
 * {@code block}.
 */
@ExtendWith(MockitoExtension.class)
class SuggestionServiceImplTest {

    @Mock
    private TaskSuggestionRepository repository;

    @Mock
    private BlacklistService blacklist;

    @Mock
    private FeatureFlagStore flagStore;

    @InjectMocks
    private SuggestionServiceImpl service;

    private SuggestionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SuggestionProperties();
        // Все дефолты используем как в production, кроме явных переопределений в тестах.
        service = new SuggestionServiceImpl(repository, blacklist, properties, flagStore);
        // lenient — флаг проверяется только в track/suggest, в block/cacheKey не зовётся
        lenient().when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(true);
    }

    // ===== track: фильтры =====

    @Test
    void track_FeatureFlagDisabled_DoesNothing() {
        when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(false);

        service.track("хлеб", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_PrivateTodo_DoesNotUpsert() {
        service.track("хлеб", true);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_NullText_DoesNothing() {
        service.track(null, false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_BlankText_DoesNothing() {
        service.track("   ", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_TooShort_DoesNothing() {
        properties.setMinPrefixLength(2);

        service.track("а", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_TooLong_DoesNothing() {
        properties.setMaxTextLength(10);

        service.track("это очень длинное предложение", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_LooksLikeEmail_DoesNothing() {
        service.track("user@mail.ru", false);
        // расширенный EMAIL_LIKE: спецсимволы +/-/. перед @
        service.track("me+tag@example.com", false);
        service.track("user-name@x.y", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_HasTwoOrMoreDigits_DoesNothing() {
        // ≥2 подряд = вероятный номер / адрес / сумма (ужесточено с 3 в panel-review)
        service.track("8927", false);
        service.track("Купить 12 чего-то", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_HasUnicodeDigits_DoesNothing() {
        // Не-ASCII цифры (полноширинные / арабско-индийские) — тоже номер/адрес.
        // \d с UNICODE_CHARACTER_CLASS ловит \p{Nd} (panel-review iter3).
        service.track("Ленина １２", false);   // full-width
        service.track("Ленина ١٢", false);    // arabic-indic

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_NoLetters_DoesNothing() {
        // emoji-only / цифры-only / пунктуация-only — мусор в словаре.
        // Letter-check срабатывает до blacklist'а, поэтому blacklist даже не зовём.
        service.track("🍞🥖🥐", false);
        service.track("???", false);
        service.track("...", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    @Test
    void track_BlacklistHit_DoesNothing() {
        when(blacklist.contains("ругательство")).thenReturn(true);

        service.track("ругательство", false);

        verify(repository, never()).upsert(anyString(), anyString());
    }

    // ===== track: happy path =====

    @Test
    void track_HappyPath_UpsertsNormalizedTextWithOriginalDisplay() {
        when(blacklist.contains(anyString())).thenReturn(false);

        service.track("Хлеб", false);

        ArgumentCaptor<String> normalized = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> display = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(normalized.capture(), display.capture());
        assertThat(normalized.getValue()).isEqualTo("хлеб");
        assertThat(display.getValue()).isEqualTo("Хлеб");
    }

    @Test
    void track_TrimWhitespace() {
        when(blacklist.contains(anyString())).thenReturn(false);

        service.track("  молоко  ", false);

        verify(repository).upsert(eq("молоко"), eq("молоко"));
    }

    @Test
    void track_NormalizeCollapsesInternalWhitespace() {
        when(blacklist.contains(anyString())).thenReturn(false);

        // «х л е б» с табуляцией / множественными пробелами нормализуется к одному
        // пробелу — дедуп ↔ симметрия с suggest.
        service.track("х л е б", false);

        verify(repository).upsert(eq("х л е б"), eq("х л е б"));
    }

    @Test
    void track_NormalizeCollapsesNbsp() {
        when(blacklist.contains(anyString())).thenReturn(false);

        // NBSP (U+00A0, частая вставка из iOS) — \s с (?U) его схлопывает, иначе
        // «хлеб белый» и «хлеб белый» — две разные записи (panel-review iter3).
        service.track("хлеб белый", false);

        // normalized — с обычным пробелом; display сохраняет оригинал (с NBSP).
        verify(repository).upsert(eq("хлеб белый"), eq("хлеб белый"));
    }

    @Test
    void track_RepositoryException_DoesNotBubbleUp() {
        when(blacklist.contains(anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(repository).upsert(anyString(), anyString());

        // НЕ должно бросить наружу — tracking не критичен
        service.track("молоко", false);
    }

    // ===== suggest =====

    @Test
    void suggest_FeatureFlagDisabled_ReturnsEmpty() {
        when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(false);

        List<SuggestionResponse> result = service.suggest("хле", 5);

        assertThat(result).isEmpty();
        verify(repository, never()).findTopByPrefix(anyString(), anyLong(), any(Pageable.class));
    }

    @Test
    void suggest_NullPrefix_ReturnsEmpty() {
        List<SuggestionResponse> result = service.suggest(null, 5);

        assertThat(result).isEmpty();
        verify(repository, never()).findTopByPrefix(anyString(), anyLong(), any(Pageable.class));
    }

    @Test
    void suggest_ShorterThanMinPrefixLength_ReturnsEmpty() {
        properties.setMinPrefixLength(3);

        List<SuggestionResponse> result = service.suggest("хл", 5);

        assertThat(result).isEmpty();
        verify(repository, never()).findTopByPrefix(anyString(), anyLong(), any(Pageable.class));
    }

    @Test
    void suggest_NormalizesPrefixBeforeQuery() {
        when(repository.findTopByPrefix(eq("хле%"), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(sample("хлеб", "Хлеб", 7)));

        List<SuggestionResponse> result = service.suggest("  Хле  ", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("Хлеб");
        verify(repository).findTopByPrefix(eq("хле%"), eq(properties.getMinFreq()), any(Pageable.class));
    }

    @Test
    void suggest_EscapesLikeWildcards() {
        // Если клиент шлёт prefix='%' или '_', сервис обязан escape'ить —
        // иначе LIKE возвращает дамп всего словаря (panel-review security#1 Critical).
        when(repository.findTopByPrefix(eq("\\%\\_a%"), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        service.suggest("%_a", 5);

        verify(repository).findTopByPrefix(eq("\\%\\_a%"), anyLong(), any(Pageable.class));
    }

    @Test
    void suggest_ClampsLimitToMax() {
        properties.setMaxLimit(10);
        when(repository.findTopByPrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        service.suggest("молок", 999);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findTopByPrefix(anyString(), anyLong(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void suggest_ClampsLimitToOne() {
        when(repository.findTopByPrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        service.suggest("молок", 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findTopByPrefix(anyString(), anyLong(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void suggest_MapsDisplayTextNotNormalized() {
        when(repository.findTopByPrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                        sample("молоко", "Молоко", 100),
                        sample("молочка", "Молочка", 50)
                ));

        List<SuggestionResponse> result = service.suggest("моло", 5);

        assertThat(result).extracting(SuggestionResponse::getText)
                .containsExactly("Молоко", "Молочка");
    }

    // ===== block =====

    @Test
    void block_NullText_ReturnsFalse() {
        assertThat(service.block(null)).isFalse();
        verify(repository, never()).block(anyString());
    }

    @Test
    void block_BlankText_ReturnsFalse() {
        assertThat(service.block("   ")).isFalse();
        verify(repository, never()).block(anyString());
    }

    @Test
    void block_TextExists_ReturnsTrueAndCallsBlock() {
        when(repository.block("молоко")).thenReturn(1);

        assertThat(service.block("Молоко")).isTrue();
        verify(repository, times(1)).block("молоко");
    }

    @Test
    void block_TextNotFound_ReturnsFalse() {
        when(repository.block("несуществует")).thenReturn(0);

        assertThat(service.block("несуществует")).isFalse();
        verify(repository, times(1)).block("несуществует");
    }

    // ===== cacheKey =====

    @Test
    void cacheKey_NormalizesPrefixAndClampsLimit() {
        properties.setMaxLimit(10);

        assertThat(service.cacheKey("  Хле  ", 5)).isEqualTo("хле:5");
        assertThat(service.cacheKey("Хле", 999)).isEqualTo("хле:10");
        assertThat(service.cacheKey("Хле", 0)).isEqualTo("хле:1");
        assertThat(service.cacheKey(null, 5)).isEqualTo(":5");
    }

    // ===== helpers =====

    private TaskSuggestion sample(String text, String display, long freq) {
        TaskSuggestion s = new TaskSuggestion();
        s.setText(text);
        s.setTextDisplay(display);
        s.setFreq(freq);
        s.setLastUsedAt(LocalDateTime.now());
        s.setBlocked(false);
        return s;
    }
}
