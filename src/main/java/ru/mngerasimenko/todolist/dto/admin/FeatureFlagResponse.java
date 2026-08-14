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

    /** PERSISTED | RUNTIME | ENV | DEFAULT — откуда пришло текущее значение. */
    private String source;

    /**
     * Долговечность ручного переключения: {@code PERSISTENT} — переживёт рестарт и деплой,
     * {@code PROCESS} — слетит на ближайшем (так ведут себя флаги защиты). Без этого поля
     * оператор не мог ответить на главный вопрос инцидента: вернётся ли фича сама.
     */
    @JsonProperty("override_lifetime")
    private String overrideLifetime;

    /** Кому флаг адресован: SERVER исполняет сервер, CLIENT — приложение (со следующего запуска). */
    private String audience;

    private String description;
}
