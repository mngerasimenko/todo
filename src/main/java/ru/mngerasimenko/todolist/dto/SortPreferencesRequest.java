package ru.mngerasimenko.todolist.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для PATCH /api/users/me/sort-preferences.
 * Частичное обновление 4 sort-настроек пользователя.
 *
 * <p>Все поля опциональные: можно обновить только нужные. Если все 4 null —
 * сервис вернёт без изменений (no-op).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SortPreferencesRequest {

    @Pattern(regexp = "^(MANUAL|ALPHABETICAL|CREATED_AT)$",
            message = "Sort mode must be MANUAL, ALPHABETICAL or CREATED_AT")
    private String listsSortMode;

    @Pattern(regexp = "^(ASC|DESC)$",
            message = "Sort direction must be ASC or DESC")
    private String listsSortDirection;

    @Pattern(regexp = "^(MANUAL|ALPHABETICAL|CREATED_AT)$",
            message = "Sort mode must be MANUAL, ALPHABETICAL or CREATED_AT")
    private String todosSortMode;

    @Pattern(regexp = "^(ASC|DESC)$",
            message = "Sort direction must be ASC or DESC")
    private String todosSortDirection;
}
