package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.admin.SuggestionReseedReport;

/**
 * Разовая ре-агрегация словаря подсказок под distinct-режим (seed 029, Server R-6).
 * <p>
 * После деплоя 028 поле {@code task_suggestion.freq} всё ещё хранит число ВХОЖДЕНИЙ
 * (seed 026 + occurrence-трекинг до 028), а {@code task_suggestion_user} пуста. Этот сервис
 * пересчитывает словарь так, чтобы {@code freq} = число РАЗНЫХ авторов, и заполняет
 * таблицу авторов — иначе going-forward пошёл бы двойной счёт.
 * <p>
 * Запускается вручную через admin-эндпоинт (super-admin), backfill → ask. Идемпотентен.
 */
public interface SuggestionReseedService {

    /**
     * Пересчитать словарь из текущих НЕ приватных задач.
     *
     * @param dryRun {@code true} — только посчитать и вернуть отчёт, ничего не записывая;
     *               {@code false} — применить (перестроить словарь + авторов в одной транзакции)
     * @return отчёт со счётчиками (что сделано / что было бы сделано)
     */
    SuggestionReseedReport reseed(boolean dryRun);
}
