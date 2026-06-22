package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.TaskSuggestion;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Реализация {@link SuggestionService}.
 * <p>
 * Track-фильтры (выбраны в плане R-6, 2026-06-05; ужесточены 2026-06-21 после panel-review):
 * <ol>
 *   <li>Приватная задача → skip (не tracking приватные)</li>
 *   <li>Пустая / короче {@code min-track-length} (храним слова от 3 символов) / длиннее {@code max-text-length} → skip</li>
 *   <li>Похоже на email → skip (символ {@code @} с буквой/цифрой/{@code +_-.} до и после)</li>
 *   <li>≥{@code 2} цифры подряд → skip (номер телефона / адрес / сумма) — ужесточено с 3</li>
 *   <li>Нет ни одной буквы (emoji-only / цифры-only / пунктуация-only) → skip</li>
 *   <li>{@link BlacklistService}-hit → skip</li>
 * </ol>
 * Если все фильтры пройдены — атомарный UPSERT через
 * {@link TaskSuggestionRepository#upsert} на нормализованной форме.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionServiceImpl implements SuggestionService {

    // EMAIL_LIKE: учитываем спецсимволы '+', '-', '.', '_' перед '@' — иначе
    // "me+tag@example.com" и "user-name@x.y" проходят как обычные подсказки.
    private static final Pattern EMAIL_LIKE = Pattern.compile(".*[\\p{L}\\p{N}._+\\-]@[\\p{L}\\p{N}.\\-].*");
    // 2 цифры подряд — порог достаточно консервативный, чтобы ловить «ул. Ленина 5»
    // и «42 76 12» (после нормализации пробелов). UNICODE_CHARACTER_CLASS: \d ловит и
    // не-ASCII цифры (арабско-индийские ١٢, полноширинные １２), иначе ПД-номер такими
    // цифрами обходит фильтр (panel-review iter3, 2026-06-22).
    private static final Pattern PHONE_LIKE = Pattern.compile(".*\\d{2,}.*", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * SpEL-условие @Cacheable: кеш «suggestions» работает, только если разрешён
     * глобальный {@link FeatureFlag#RESPONSE_CACHE} И сам словарь подсказок
     * {@link FeatureFlag#SUGGESTIONS}. При выключении SUGGESTIONS — кеш не читается
     * и не пишется, и {@code suggest()} мгновенно отдаёт пустой список без 60с TTL-staleness.
     */
    private static final String CACHE_CONDITION_BOTH_FLAGS =
            RedisCacheConfig.CACHE_CONDITION
                    + " and @featureFlagStore.isEnabled(T(ru.mngerasimenko.todolist.featureflags.FeatureFlag).SUGGESTIONS)";

    private final TaskSuggestionRepository repository;
    private final BlacklistService blacklist;
    private final SuggestionProperties properties;
    private final FeatureFlagStore flagStore;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void track(String rawText, boolean isPrivate) {
        if (!flagStore.isEnabled(FeatureFlag.SUGGESTIONS)) {
            return;
        }
        if (isPrivate) {
            return;
        }
        if (rawText == null) {
            return;
        }
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.length() < properties.getMinTrackLength()
                || trimmed.length() > properties.getMaxTextLength()) {
            return;
        }
        if (EMAIL_LIKE.matcher(trimmed).matches() || PHONE_LIKE.matcher(trimmed).matches()) {
            return;
        }
        // Emoji-only / пунктуация-only / цифры-only: codepoint-aware проверка,
        // не ломает кириллицу / латиницу / mixed-scripts. Защищает от «🍞🥖🥐»-мусора в БД.
        if (trimmed.codePoints().noneMatch(Character::isLetter)) {
            return;
        }
        if (blacklist.contains(trimmed)) {
            log.debug("[suggestions] blacklist hit, skip tracking for length={}", trimmed.length());
            return;
        }
        String normalized = normalize(trimmed);
        if (normalized.isEmpty() || normalized.length() > properties.getMaxTextLength()) {
            return;
        }
        try {
            // text_display унифицирован в нижний регистр (= normalized): пользователи
            // в основном пишут продукты с маленькой (owner-решение 2026-06-22).
            repository.upsert(normalized, normalized);
        } catch (RuntimeException ex) {
            // Tracking не должен ломать создание задачи — это nice-to-have. Логируем и проглатываем.
            log.warn("[suggestions] не удалось обновить словарь, length={}: {}",
                    trimmed.length(), ex.toString());
        }
    }

    @Override
    @Cacheable(
            cacheNames = RedisCacheConfig.SUGGESTIONS,
            key = "#root.target.cacheKey(#rawPrefix, #limit)",
            condition = CACHE_CONDITION_BOTH_FLAGS
    )
    @Transactional(readOnly = true)
    public List<SuggestionResponse> suggest(String rawPrefix, int limit) {
        if (!flagStore.isEnabled(FeatureFlag.SUGGESTIONS)) {
            return List.of();
        }
        if (rawPrefix == null) {
            return List.of();
        }
        String normalized = normalize(rawPrefix.trim());
        if (normalized.length() < properties.getMinPrefixLength()) {
            return List.of();
        }
        int safeLimit = Math.min(
                Math.max(limit, 1),
                properties.getMaxLimit()
        );
        // Экранируем LIKE-метасимволы '%','_','\\' в normalized, иначе анонимный
        // клиент через prefix='%' получает дамп всего словаря (LIKE '%%' матчит всё)
        // и заодно ломает индекс idx_task_suggestion_prefix → Seq Scan → DoS.
        // ESCAPE-clause включается в JPQL findTopByPrefix.
        String escaped = escapeLikePattern(normalized);
        List<TaskSuggestion> rows = repository.findTopByPrefix(
                escaped + "%",
                properties.getMinFreq(),
                PageRequest.of(0, safeLimit)
        );
        return rows.stream()
                .map(s -> SuggestionResponse.builder().text(s.getTextDisplay()).build())
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisCacheConfig.SUGGESTIONS, allEntries = true)
    public boolean block(String rawText) {
        if (rawText == null) {
            return false;
        }
        String normalized = normalize(rawText.trim());
        if (normalized.isEmpty()) {
            return false;
        }
        int affected = repository.block(normalized);
        if (affected > 0) {
            log.info("[suggestions] заблокирована запись словаря, length={}", normalized.length());
            return true;
        }
        return false;
    }

    /**
     * Ключ кеша для {@link #suggest}. {@link Cacheable} SpEL не может звать private-методы,
     * поэтому метод public. Намеренно использует нормализованный prefix, чтобы запросы
     * «Хле» и «хле» делили один кеш-ключ.
     */
    public String cacheKey(String rawPrefix, int limit) {
        String normalized = rawPrefix == null ? "" : normalize(rawPrefix.trim());
        int safeLimit = Math.min(
                Math.max(limit, 1),
                properties.getMaxLimit()
        );
        return normalized + ":" + safeLimit;
    }

    /**
     * Нормализация: trim, схлопывание любых whitespace-последовательностей в один пробел,
     * нижний регистр по {@link Locale#ROOT}. Схлопывание пробелов важно для дедупа
     * («хлеб» и «х л е б» — одна запись) и симметрии track ↔ suggest.
     */
    private String normalize(String text) {
        // (?U) — \s ловит Unicode-пробелы (NBSP U+00A0, narrow-NBSP, ideographic space),
        // иначе вставка из iOS с NBSP даёт дубликат записи (panel-review iter3, 2026-06-22).
        return text.replaceAll("(?U)\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Экранирует LIKE-метасимволы (%, _, \) обратной чертой. Парная сторона —
     * {@code ESCAPE '\\'} в JPQL-запросе {@link TaskSuggestionRepository#findTopByPrefix}.
     */
    private String escapeLikePattern(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
