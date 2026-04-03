package ru.mngerasimenko.todolist.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Ответ со статистикой использования приложения.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UsageStatisticsResponse {

    @JsonProperty("generated_at")
    private String generatedAt;

    @JsonProperty("period_hours")
    private long periodHours;

    private UserStats users;
    private ListStats lists;
    private TaskStats tasks;
    private ActivityStats activity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserStats {
        private long total;

        @JsonProperty("new_in_period")
        private long newInPeriod;

        @JsonProperty("new_user_names")
        private List<String> newUserNames;

        @JsonProperty("email_verified")
        private long emailVerified;

        @JsonProperty("email_verification_rate")
        private double emailVerificationRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListStats {
        private long total;

        @JsonProperty("new_in_period")
        private long newInPeriod;

        @JsonProperty("avg_lists_per_user")
        private double avgListsPerUser;

        @JsonProperty("shared_lists")
        private long sharedLists;

        @JsonProperty("avg_members_per_list")
        private double avgMembersPerList;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskStats {
        private long total;

        @JsonProperty("new_in_period")
        private long newInPeriod;

        @JsonProperty("completed_total")
        private long completedTotal;

        @JsonProperty("completed_in_period")
        private long completedInPeriod;

        @JsonProperty("pending_total")
        private long pendingTotal;

        @JsonProperty("completion_rate")
        private double completionRate;

        @JsonProperty("avg_tasks_per_user")
        private double avgTasksPerUser;

        @JsonProperty("avg_tasks_per_list")
        private double avgTasksPerList;

        @JsonProperty("private_tasks")
        private long privateTasks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityStats {
        @JsonProperty("active_users_last_24h")
        private long activeUsersLast24h;

        @JsonProperty("active_users_last_7d")
        private long activeUsersLast7d;

        @JsonProperty("active_invite_tokens")
        private long activeInviteTokens;
    }
}
