package ru.mngerasimenko.todolist.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Результат переключения флага через {@code PUT /api/admin/flags/{name}/{value}}.
 *
 * <p>Раньше эндпоинт отвечал 204 и молчал о том, сохранилось ли переключение. Для аварийного
 * выключателя это худший исход: админ выключает сломанную функцию, видит успех, уходит — а
 * значение осталось только в памяти и вернётся на ближайшем деплое.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagUpdateResponse {

    /** Имя флага — то же, что в URL. */
    private String name;

    /** Значение, которое теперь действует. Применяется всегда и немедленно. */
    private boolean enabled;

    /**
     * Класс долговечности флага: {@code PERSISTENT} — переключение хранится в БД и переживает
     * рестарт, {@code PROCESS} — живёт до ближайшего (так ведут себя флаги защиты).
     *
     * <p>Без этого поля {@code persisted: false} нельзя истолковать: «процессный флаг, всё
     * штатно» и «флаг фичи, но запись в БД не удалась» — совершенно разные ситуации, а разбирать
     * их приходится как раз во время инцидента.
     */
    @JsonProperty("override_lifetime")
    private String overrideLifetime;

    /**
     * Только для {@code PUT}: сохранено ли переключение, то есть переживёт ли оно рестарт.
     *
     * <p>{@code false} штатно для процессных флагов; для флага фичи означает, что запись не
     * удалась — значение действует, но слетит на ближайшем рестарте (в логе ERROR).
     */
    private Boolean persisted;

    /**
     * Только для {@code DELETE}: убрана ли сохранённая строка.
     *
     * <p>{@code false} означает, что строка осталась в БД и прежнее значение вернётся после
     * рестарта. Отдельное поле, а не общий {@code persisted}: одно и то же слово для «сохранено»
     * и «удалено» читается противоположным образом, и на этом уже один раз обожглись.
     */
    private Boolean cleared;
}
