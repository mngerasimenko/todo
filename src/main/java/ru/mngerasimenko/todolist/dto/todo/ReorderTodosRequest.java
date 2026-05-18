package ru.mngerasimenko.todolist.dto.todo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO запроса PATCH /api/lists/{id}/todos/reorder — bulk-обновление позиций задач
 * внутри списка. Позиция общая per-список (все участники видят один порядок).
 */
@Data
@NoArgsConstructor
public class ReorderTodosRequest {

    @NotEmpty(message = "Items list must not be empty")
    @Valid
    private List<Item> items;

    @Data
    @NoArgsConstructor
    public static class Item {
        @NotNull(message = "id is required")
        private Long id;

        @PositiveOrZero(message = "position must be >= 0")
        @NotNull(message = "position is required")
        private Integer position;
    }
}
