package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO ответа со статусом подписки пользователя.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubscriptionStatusResponse {

    @JsonProperty("subscription_type")
    private String subscriptionType;

    @JsonProperty("subscription_expires_at")
    private LocalDateTime subscriptionExpiresAt;

    @JsonProperty("is_beta_tester")
    private boolean betaTester;

    private Limits limits;

    private Usage usage;

    /**
     * Лимиты текущей подписки.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Limits {
        @JsonProperty("max_lists")
        private int maxLists;

        @JsonProperty("max_tasks_per_list")
        private int maxTasksPerList;

        @JsonProperty("max_members_per_list")
        private int maxMembersPerList;

        @JsonProperty("private_tasks_allowed")
        private boolean privateTasksAllowed;
    }

    /**
     * Текущее использование ресурсов.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("lists_count")
        private long listsCount;

        @JsonProperty("can_create_list")
        private boolean canCreateList;
    }
}
