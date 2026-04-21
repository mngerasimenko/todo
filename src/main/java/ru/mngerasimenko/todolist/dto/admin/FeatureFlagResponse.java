package ru.mngerasimenko.todolist.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Текущее состояние feature-флага для ответа GET /api/admin/flags.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagResponse {

    private String name;
    private boolean enabled;

    @JsonProperty("default_value")
    private boolean defaultValue;

    /** RUNTIME | ENV | DEFAULT — откуда пришло текущее значение. */
    private String source;

    private String description;
}
