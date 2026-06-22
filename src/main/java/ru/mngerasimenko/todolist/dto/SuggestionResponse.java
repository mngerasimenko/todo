package ru.mngerasimenko.todolist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Элемент ответа GET /api/suggestions: одно слово/фраза-подсказка в исходном написании
 * (как первый раз ввёл первый пользователь). Возвращается без частоты и других метаданных —
 * клиент только подставляет в input.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionResponse {
    private String text;
}
