package ru.mngerasimenko.todolist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Элемент ответа GET /api/suggestions/all — bulk-выгрузка всего видимого словаря
 * подсказок для локального кэша на клиенте (Server R-7).
 * <p>
 * В отличие от {@link SuggestionResponse} (отдаёт только {@code text} для подстановки),
 * здесь нужны оба представления и частота:
 * <ul>
 *   <li>{@code text} — нормализованный ключ (lower + collapse-spaces), по нему клиент делает
 *       prefix-match своего нормализованного ввода;</li>
 *   <li>{@code textDisplay} — исходное написание, его клиент подставляет в поле;</li>
 *   <li>{@code freq} — число РАЗНЫХ авторов, для ранжирования (ORDER BY freq DESC) локально.</li>
 * </ul>
 * Словарь публичен по замыслу (обезличен: k-анонимность {@code freq >= minFreq} + PII-фильтры),
 * поэтому выгрузка целиком не добавляет новой утечки сверх постраничного {@code /api/suggestions}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionBulkResponse {
    private String text;
    private String textDisplay;
    private long freq;
}
