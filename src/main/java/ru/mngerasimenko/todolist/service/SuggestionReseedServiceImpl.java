package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.dto.admin.SuggestionReseedReport;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Реализация {@link SuggestionReseedService} (seed 029).
 * <p>
 * Алгоритм (вся работа — в одной транзакции, нет окна пустого словаря для readers):
 * <ol>
 *   <li>Постранично (keyset по {@code id}) прочитать НЕ приватные задачи; для каждой —
 *       расшифрованный текст прогнать через {@link SuggestionTextFilter} (те же фильтры, что
 *       live-трекинг), сгруппировать {@code normalized → множество разных userId}.</li>
 *   <li>Записать (если не dry-run): удалить НЕ заблокированные строки → для каждой строки с
 *       {@code distinct-авторов ≥ minFreq} (и не заблокированной) выставить {@code freq=distinctCount}
 *       и записать авторов псевдонимами {@code blindIndex(normalized + ":" + userId)}.</li>
 *   <li>Редакционные глаголы ({@link SuggestionSeedVerbs}) — floor {@code freq=minFreq} без авторов,
 *       если не всплыли из реальных данных и проходят те же фильтры (blacklist и т.п.).</li>
 * </ol>
 * Корректность хеша гарантируется переиспользованием боевого {@link CryptoService#blindIndex},
 * корректность фильтров — переиспользованием {@link SuggestionTextFilter}.
 * <p>
 * Защита от параллельного запуска — {@code pg_advisory_xact_lock} (второй прогон → 409).
 * Кеш подсказок инвалидируется ПОСЛЕ commit'а (afterCommit), чтобы readers не перезаполнили
 * кеш ещё не закоммиченными данными.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionReseedServiceImpl implements SuggestionReseedService {

    private static final int PAGE_SIZE = 500;
    private static final int TOP_SAMPLE_SIZE = 15;
    /** Произвольный фиксированный ключ pg_advisory_xact_lock — сериализует прогоны reseed. */
    private static final long RESEED_ADVISORY_LOCK_KEY = 6_029L;

    private final TodoRepository todoRepository;
    private final TaskSuggestionRepository suggestionRepository;
    private final SuggestionTextFilter filter;
    private final SuggestionProperties properties;
    private final CryptoService cryptoService;
    private final CacheManager cacheManager;

    @Override
    @Transactional
    public SuggestionReseedReport reseed(boolean dryRun) {
        long minFreq = properties.getMinFreq();

        // Защита от параллельного reseed: xact-lock держится до конца транзакции.
        // Нужен только для пишущего прогона; dry-run ничего не меняет.
        if (!dryRun && !suggestionRepository.tryReseedAdvisoryLock(RESEED_ADVISORY_LOCK_KEY)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reseed словаря уже выполняется");
        }

        // (1) Агрегация distinct-авторов по нормализованным строкам.
        Aggregation agg = aggregate();

        // (2) Заблокированные admin'ом строки reseed не трогает.
        Set<String> blocked = new HashSet<>(suggestionRepository.findBlockedTexts());

        // Строки, которые попадут в словарь из реальных данных: distinct-авторов ≥ minFreq и не заблокированы.
        List<Map.Entry<String, Set<Long>>> kept = agg.distinctAuthorsByText().entrySet().stream()
                .filter(e -> e.getValue().size() >= minFreq)
                .filter(e -> !blocked.contains(e.getKey()))
                .toList();

        // Редакционные глаголы под floor: не заблокированы, проходят те же track-фильтры
        // (blacklist/длина/...) — чтобы reseed не вводил строки, которые live-track отверг бы,
        // и не всплыли из реальных данных с ≥ minFreq (тогда реальная агрегация выигрывает).
        List<String> flooredVerbs = SuggestionSeedVerbs.EDITORIAL_VERBS.stream()
                .filter(v -> !blocked.contains(v))
                .filter(v -> filter.normalizeIfTrackable(v, false).isPresent())
                .filter(v -> {
                    Set<Long> authors = agg.distinctAuthorsByText().get(v);
                    return authors == null || authors.size() < minFreq;
                })
                .toList();

        long contributorRows = kept.stream().mapToLong(e -> e.getValue().size()).sum();
        long nonBlockedExisting = suggestionRepository.countNonBlocked();

        if (!dryRun) {
            applyReseed(kept, flooredVerbs, minFreq);
            registerCacheEvictAfterCommit();
            log.info("[reseed] применено: kept={}, contributorRows={}, verbsFloored={}, deletedNonBlocked={}, minFreq={}",
                    kept.size(), contributorRows, flooredVerbs.size(), nonBlockedExisting, minFreq);
        } else {
            log.info("[reseed] dry-run: kept={}, contributorRows={}, verbsFloored={}, wouldDelete={}, minFreq={}",
                    kept.size(), contributorRows, flooredVerbs.size(), nonBlockedExisting, minFreq);
        }

        return SuggestionReseedReport.builder()
                .dryRun(dryRun)
                .todosScanned(agg.scanned())
                .todosTrackable(agg.trackable())
                .distinctProductsTotal(agg.distinctAuthorsByText().size())
                .productsKept(kept.size())
                .contributorRowsWritten(contributorRows)
                .editorialVerbsFloored(flooredVerbs.size())
                .blockedPreserved(blocked.size())
                .nonBlockedDeleted(nonBlockedExisting)
                .minFreqApplied(minFreq)
                .topSample(buildTopSample(kept, flooredVerbs, minFreq))
                .build();
    }

    /**
     * Перестроить словарь. Удаляет НЕ заблокированные строки (FK CASCADE чистит их авторов),
     * затем вставляет kept-строки с distinct-freq + авторов-псевдонимов и editorial-floor глаголы.
     * Всё в транзакции вызывающего {@link #reseed} (REQUIRED) — атомарно для readers.
     * <p>
     * ВАЖНО: метод обязан выполняться в транзакции вызывающего; собственная {@code @Transactional}
     * здесь была бы no-op (private self-invocation), поэтому её нет намеренно.
     */
    private void applyReseed(List<Map.Entry<String, Set<Long>>> kept,
                             List<String> flooredVerbs,
                             long minFreq) {
        suggestionRepository.deleteAllNonBlocked();

        for (Map.Entry<String, Set<Long>> entry : kept) {
            String normalized = entry.getKey();
            Set<Long> authors = entry.getValue();
            // freq = размер множества РАЗНЫХ userId. Каждый userId даёт уникальный вход
            // blindIndex(normalized + ":" + userId) → уникальный хеш → ровно одна строка-автор,
            // поэтому freq == COUNT(task_suggestion_user) по построению.
            suggestionRepository.insertReseed(normalized, normalized, authors.size());
            for (Long userId : authors) {
                String userHash = cryptoService.blindIndex(normalized + ":" + userId);
                suggestionRepository.addSuggestionUser(normalized, userHash);
            }
        }

        // Редакционные глаголы — синтетический floor без строк-авторов (могут дрейфовать вверх,
        // когда реальные пользователи их введут после деплоя; это допустимо и только вверх).
        for (String verb : flooredVerbs) {
            suggestionRepository.insertReseed(verb, verb, minFreq);
        }
    }

    /**
     * Прочитать НЕ приватные задачи keyset-пагинацией (id ASC, id &gt; afterId) — устойчиво к
     * конкурентным вставкам (в отличие от OFFSET). Расшифровать текст (через converter), прогнать
     * через track-фильтры и собрать {@code normalized → множество разных userId}.
     */
    private Aggregation aggregate() {
        Map<String, Set<Long>> distinctAuthorsByText = new HashMap<>();
        long scanned = 0;
        long trackable = 0;

        long afterId = 0L;
        List<Todo> batch;
        do {
            batch = todoRepository.findNonPrivateForReseed(afterId, PageRequest.of(0, PAGE_SIZE));
            for (Todo todo : batch) {
                scanned++;
                afterId = todo.getId(); // keyset-курсор двигаем всегда, даже если строка отфильтрована
                Optional<String> normalized = filter.normalizeIfTrackable(todo.getName(), todo.getIsPrivate());
                if (normalized.isEmpty()) {
                    continue;
                }
                Long userId = todo.getUserId();
                if (userId == null) {
                    continue; // user_id NOT NULL по схеме — защитная проверка
                }
                trackable++;
                distinctAuthorsByText
                        .computeIfAbsent(normalized.get(), k -> new HashSet<>())
                        .add(userId);
            }
        } while (batch.size() == PAGE_SIZE);

        return new Aggregation(distinctAuthorsByText, scanned, trackable);
    }

    /**
     * Инвалидировать кеш подсказок ПОСЛЕ commit'а транзакции — иначе между eviction'ом и commit'ом
     * конкурентный reader перечитал бы старые (ещё закоммиченные) данные и перезаполнил кеш на TTL.
     */
    private void registerCacheEvictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache cache = cacheManager.getCache(RedisCacheConfig.SUGGESTIONS);
                    if (cache != null) {
                        cache.clear();
                    }
                }
            });
        }
    }

    private List<SuggestionReseedReport.TopEntry> buildTopSample(
            List<Map.Entry<String, Set<Long>>> kept,
            List<String> flooredVerbs,
            long minFreq) {
        List<SuggestionReseedReport.TopEntry> sample = new ArrayList<>();
        kept.forEach(e -> sample.add(new SuggestionReseedReport.TopEntry(e.getKey(), e.getValue().size())));
        flooredVerbs.forEach(v -> sample.add(new SuggestionReseedReport.TopEntry(v, minFreq)));
        return sample.stream()
                .sorted(Comparator.comparingLong(SuggestionReseedReport.TopEntry::freq).reversed())
                .limit(TOP_SAMPLE_SIZE)
                .toList();
    }

    /** Результат фазы агрегации: distinct-авторы по строкам + счётчики просмотренного. */
    private record Aggregation(Map<String, Set<Long>> distinctAuthorsByText, long scanned, long trackable) {
    }
}
