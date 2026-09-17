package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO запроса PATCH /api/lists/reorder — bulk-обновление позиций списков
 * для текущего юзера (per-user sorting).
 */
@Data
@NoArgsConstructor
public class ReorderListsRequest {

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
