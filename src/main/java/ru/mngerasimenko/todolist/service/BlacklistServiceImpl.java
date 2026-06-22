package ru.mngerasimenko.todolist.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory реализация {@link BlacklistService}: список корней грузится один раз при старте
 * приложения и проверяется через substring-сравнение по нормализованной форме входа.
 * <p>
 * <b>Почему не Bloom Filter (как изначально в плане R-6):</b> на ~50–200 корней HashSet даёт
 * O(1) lookup без false-positive'ов и весит ~10 KB. Bloom Filter имеет смысл от тысяч записей.
 * <p>
 * <b>Нормализация входа (де-обфускация):</b> приводим строку к нижнему регистру, переводим
 * латинские «двойники» русских букв и цифры-имитации (a→а, c→с, e→е, o→о, p→р, x→х, y→у,
 * 0→о, 1→и, 3→з, 4→ч) обратно, и выбрасываем всё не-буквенное (пробелы, точки, звёздочки,
 * подчёркивания, эмодзи). Это ловит «х*йня», «h0yня», «х_у_й». Намеренно простая, не stemming.
 */
@Slf4j
@Service
public class BlacklistServiceImpl implements BlacklistService {

    private static final String BLACKLIST_RESOURCE = "suggestion_blacklist.txt";

    /**
     * Замены латиница/цифры → кириллица. Покрывает базовые leetspeak-варианты.
     * Не претендует на полноту — цель «снять 90% низкоусилого обхода».
     */
    private static final Map<Character, Character> DEOBFUSCATION = Map.ofEntries(
            Map.entry('0', 'о'),
            Map.entry('1', 'и'),
            Map.entry('3', 'з'),
            Map.entry('4', 'ч'),
            Map.entry('@', 'а'),
            Map.entry('$', 'с'),
            Map.entry('a', 'а'),
            Map.entry('b', 'в'),
            Map.entry('c', 'с'),
            Map.entry('e', 'е'),
            Map.entry('h', 'н'),
            Map.entry('k', 'к'),
            Map.entry('m', 'м'),
            Map.entry('o', 'о'),
            Map.entry('p', 'р'),
            Map.entry('t', 'т'),
            Map.entry('x', 'х'),
            Map.entry('y', 'у')
    );

    // volatile — формально безопасная публикация после @PostConstruct (panel-review
    // concurrency#3, 2026-06-21). Без volatile happens-before между @PostConstruct
    // и первым contains()-вызовом из другого треда формально не гарантирована JMM.
    private volatile Set<String> roots = Set.of();

    @PostConstruct
    void loadBlacklist() {
        Set<String> loaded = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(BLACKLIST_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                loaded.add(normalize(trimmed));
            }
        } catch (IOException e) {
            log.error("[blacklist] Не удалось загрузить {}, словарь пуст — фильтрация отключена",
                    BLACKLIST_RESOURCE, e);
            return;
        }
        this.roots = Set.copyOf(loaded);
        log.info("[blacklist] Загружено корней: {}", roots.size());
    }

    @Override
    public boolean contains(String text) {
        if (text == null || text.isBlank() || roots.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String root : roots) {
            if (normalized.contains(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Лоукейс + де-обфускация + удаление всех не-буквенных символов.
     * Латиница (после переноса в кириллические двойники) и кириллица остаются;
     * пробелы, цифры (после переноса), знаки препинания, эмодзи отбрасываются.
     */
    private String normalize(String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            Character mapped = DEOBFUSCATION.get(c);
            if (mapped != null) {
                sb.append(mapped);
                continue;
            }
            if (Character.isLetter(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
