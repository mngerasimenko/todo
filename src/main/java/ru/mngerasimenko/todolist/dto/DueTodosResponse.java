package ru.mngerasimenko.todolist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Ответ эндпоинта «Сегодня» — задачи со сроком, сгруппированные относительно
 * текущей даты (в поясе каждой задачи): просроченные, сегодняшние, ближайшие.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DueTodosResponse {

    private List<TodoResponse> overdue;

    private List<TodoResponse> today;

    private List<TodoResponse> upcoming;
}
