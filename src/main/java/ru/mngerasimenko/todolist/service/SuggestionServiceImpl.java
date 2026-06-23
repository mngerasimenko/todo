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
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.TaskSuggestion;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.List;
import java.util.Optional;

/**
 * Реализация {@link SuggestionService}.
 * <p>
 * Нормализация и track-фильтры вынесены в {@link SuggestionTextFilter} (общие с reseed 029).
 * Если все фильтры пройдены — distinct-учёт через
 * {@link TaskSuggestionRepository#ensureSuggestion} + {@link TaskSuggestionRepository#addSuggestionUser}
 * + {@link TaskSuggestionRepository#incrementFreq}: {@code freq} = число РАЗНЫХ авторов строки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionServiceImpl implements SuggestionService {

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
    private final SuggestionTextFilter filter;
    private final SuggestionProperties properties;
    private final FeatureFlagStore flagStore;
    private final CryptoService cryptoService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void track(String rawText, boolean isPrivate, Long userId) {
        if (!flagStore.isEnabled(FeatureFlag.SUGGESTIONS)) {
            return;
        }
        // userId обязателен для distinct-учёта (k-анонимность). Без автора нельзя сосчитать
        // РАЗНЫХ пользователей, поэтому — skip (в норме хук всегда передаёт id автора задачи).
        if (userId == null) {
            return;
        }
        Optional<String> trackable = filter.normalizeIfTrackable(rawText, isPrivate);
        if (trackable.isEmpty()) {
            return;
        }
        String normalized = trackable.get();
        try {
            // Distinct-учёт: text_display унифицирован в нижний регистр (= normalized) —
            // пользователи в основном пишут продукты с маленькой (owner-решение 2026-06-22).
            // (1) гарантируем строку (freq не трогаем), (2) отмечаем автора ПСЕВДОНИМОМ,
            // (3) если автор новый для строки — повышаем freq. Так freq = число РАЗНЫХ авторов.
            // userHash = HMAC(ключ, слово + ':' + userId): per-text псевдоним, необратим к user_id;
            // один юзер по разным словам даёт разные хеши → его слова не связать (152-ФЗ минимизация).
            repository.ensureSuggestion(normalized, normalized);
            String userHash = cryptoService.blindIndex(normalized + ":" + userId);
            if (repository.addSuggestionUser(normalized, userHash) > 0) {
                repository.incrementFreq(normalized);
            }
        } catch (RuntimeException ex) {
            // Tracking не должен ломать создание задачи — это nice-to-have. Логируем и проглатываем.
            log.warn("[suggestions] не удалось обновить словарь, length={}: {}",
                    normalized.length(), ex.toString());
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
        String normalized = filter.normalize(rawPrefix.trim());
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
        String normalized = filter.normalize(rawText.trim());
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
        String normalized = rawPrefix == null ? "" : filter.normalize(rawPrefix.trim());
        int safeLimit = Math.min(
                Math.max(limit, 1),
                properties.getMaxLimit()
        );
        return normalized + ":" + safeLimit;
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
