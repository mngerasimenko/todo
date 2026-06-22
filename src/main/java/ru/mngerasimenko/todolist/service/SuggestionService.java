package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.SuggestionResponse;

import java.util.List;

/**
 * Глобальный словарь подсказок при вводе задачи (Server R-6).
 * <p>
 * Пополняется автоматически через хук в {@code TodoServiceImpl.createTodo} (после commit'а
 * транзакции, чтобы tracking не откатывался при rollback'е задачи).
 * Читается публично через {@code GET /api/suggestions} (без JWT — гости тоже зовут).
 */
public interface SuggestionService {

    /**
     * Зарегистрировать использование строки в словаре.
     * <p>
     * Применяет цепочку фильтров: приватная задача → skip, пустая/короткая/слишком длинная →
     * skip, выглядит как email/телефон → skip, содержит мат → skip. Если фильтры пройдены —
     * атомарный UPSERT в {@code task_suggestion} с {@code freq = freq + 1}.
     * <p>
     * Метод никогда не бросает исключение наружу: ошибка сохранения логируется и
     * проглатывается (tracking — не критичный путь для пользователя). Это важно, потому что
     * вызывается из {@code TransactionSynchronization.afterCommit()}, и наружу уже нечего
     * рассказать клиенту.
     *
     * @param rawText сырой текст задачи (как ввёл пользователь, без обработки)
     * @param isPrivate true, если задача создана как приватная (не tracking)
     */
    void track(String rawText, boolean isPrivate);

    /**
     * Топ-N подсказок по prefix. Возвращает пустой список если префикс короче
     * {@code app.suggestions.min-prefix-length} или фича выключена флагом.
     *
     * @param rawPrefix префикс задачи (как ввёл пользователь)
     * @param limit запрошенное число подсказок; обрезается до {@code app.suggestions.max-limit}
     */
    List<SuggestionResponse> suggest(String rawPrefix, int limit);

    /**
     * Принудительно скрыть строку из выдачи (admin API). Не удаляет запись —
     * только проставляет {@code blocked = true}, чтобы счётчик частоты сохранился
     * для будущей разблокировки.
     *
     * @return true, если строка существовала и переведена в blocked; false, если строки нет
     */
    boolean block(String rawText);
}
