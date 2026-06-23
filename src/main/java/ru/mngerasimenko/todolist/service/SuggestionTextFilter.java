package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Единая точка нормализации и track-фильтров словаря подсказок (Server R-6).
 * <p>
 * Вынесено из {@link SuggestionServiceImpl}, чтобы live-трекинг ({@code track})
 * и разовая ре-агрегация ({@link SuggestionReseedService}, seed 029) применяли
 * <b>идентичную</b> цепочку фильтров и нормализацию. Расхождение фильтров сделало бы
 * seed несовместимым с going-forward учётом — это юридически значимо (k-анонимность).
 * <p>
 * Цепочка фильтров (см. {@link #normalizeIfTrackable}):
 * <ol>
 *   <li>Приватная задача → skip</li>
 *   <li>Пустая / короче {@code min-track-length} / длиннее {@code max-text-length} → skip</li>
 *   <li>Похоже на email → skip</li>
 *   <li>≥2 цифры подряд → skip (телефон / адрес / сумма)</li>
 *   <li>Нет ни одной буквы (emoji-only / цифры-only / пунктуация-only) → skip</li>
 *   <li>{@link BlacklistService}-hit → skip</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SuggestionTextFilter {

    // EMAIL_LIKE: учитываем спецсимволы '+', '-', '.', '_' перед '@' — иначе
    // "me+tag@example.com" и "user-name@x.y" проходят как обычные подсказки.
    private static final Pattern EMAIL_LIKE = Pattern.compile(".*[\\p{L}\\p{N}._+\\-]@[\\p{L}\\p{N}.\\-].*");
    // 2 цифры подряд — порог достаточно консервативный, чтобы ловить «ул. Ленина 5»
    // и «42 76 12» (после нормализации пробелов). UNICODE_CHARACTER_CLASS: \d ловит и
    // не-ASCII цифры (арабско-индийские ١٢, полноширинные １２), иначе ПД-номер такими
    // цифрами обходит фильтр (panel-review iter3, 2026-06-22).
    private static final Pattern PHONE_LIKE = Pattern.compile(".*\\d{2,}.*", Pattern.UNICODE_CHARACTER_CLASS);

    private final BlacklistService blacklist;
    private final SuggestionProperties properties;

    /**
     * Прогнать сырой текст задачи через всю цепочку track-фильтров.
     *
     * @param rawText   сырой текст задачи (как ввёл пользователь)
     * @param isPrivate приватная ли задача (приватные не tracking)
     * @return нормализованный текст (PK словаря), если строка прошла все фильтры; иначе {@link Optional#empty()}
     */
    public Optional<String> normalizeIfTrackable(String rawText, boolean isPrivate) {
        if (isPrivate) {
            return Optional.empty();
        }
        if (rawText == null) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.length() < properties.getMinTrackLength()
                || trimmed.length() > properties.getMaxTextLength()) {
            return Optional.empty();
        }
        if (EMAIL_LIKE.matcher(trimmed).matches() || PHONE_LIKE.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        // Emoji-only / пунктуация-only / цифры-only: codepoint-aware проверка,
        // не ломает кириллицу / латиницу / mixed-scripts.
        if (trimmed.codePoints().noneMatch(Character::isLetter)) {
            return Optional.empty();
        }
        if (blacklist.contains(trimmed)) {
            return Optional.empty();
        }
        String normalized = normalize(trimmed);
        if (normalized.isEmpty() || normalized.length() > properties.getMaxTextLength()) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * Нормализация: схлопывание любых whitespace-последовательностей в один пробел,
     * trim, нижний регистр по {@link Locale#ROOT}. Схлопывание пробелов важно для дедупа
     * («хлеб» и «х л е б» — одна запись) и симметрии track ↔ suggest ↔ reseed.
     * <p>
     * {@code (?U)} — {@code \s} ловит Unicode-пробелы (NBSP U+00A0, narrow-NBSP, ideographic space),
     * иначе вставка из iOS с NBSP даёт дубликат записи.
     */
    public String normalize(String text) {
        return text.replaceAll("(?U)\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
