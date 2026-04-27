package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
