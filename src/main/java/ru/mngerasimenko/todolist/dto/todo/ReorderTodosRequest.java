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

    @NotEmpty(message = "{validation.reorder.items.required}")
    @Valid
    private List<Item> items;

    @Data
    @NoArgsConstructor
    public static class Item {
        @NotNull(message = "{validation.reorder.id.required}")
        private Long id;

        @PositiveOrZero(message = "{validation.reorder.position.non-negative}")
        @NotNull(message = "{validation.reorder.position.required}")
        private Integer position;
    }
}
