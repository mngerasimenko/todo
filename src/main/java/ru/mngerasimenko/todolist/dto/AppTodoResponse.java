package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO ответа статуса приложения.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppTodoResponse {

    private Boolean status;

    private String appName;

    private String version;

    @JsonProperty("min_android_version")
    private Integer minAndroidVersion;

    @JsonProperty("latest_android_version")
    private Integer latestAndroidVersion;

    @JsonProperty("smtp_healthy")
    private Boolean smtpHealthy;

    @JsonProperty("firebase_healthy")
    private Boolean firebaseHealthy;

    @JsonProperty("redis_healthy")
    private Boolean redisHealthy;

    /**
     * Feature-флаги, которые исполняет само приложение (см. {@code Audience.CLIENT}):
     * имя флага → текущее значение. Клиент забирает их при старте и кэширует локально.
     *
     * <p>Пустая карта отдаётся как {@code {}}, а НЕ опускается: клиент различает «поля нет»
     * (сервер старее, значения не трогаем) и «пришёл пустой снимок» (клиентских флагов не
     * осталось, сбрасываем к дефолтам). Схлопни мы эти случаи, выключенный когда-то флаг
     * завис бы на устройствах навсегда после снятия последнего клиентского флага с сервера.
     *
     * <p>Едет здесь, а не отдельным эндпоинтом: {@code /api/status} и так дёргается на каждом
     * запуске, публичен (работает у гостя) и уже возит клиенту управляющие данные
     * ({@code min_android_version}). Отдельный запрос был бы лишним round-trip'ом.
     */
    @JsonProperty("client_flags")
    private Map<String, Boolean> clientFlags;
}
