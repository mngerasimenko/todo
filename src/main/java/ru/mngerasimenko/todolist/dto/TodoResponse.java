package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoResponse {

    private Long id;

    private String name;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("completed_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    private Boolean done;

    @JsonProperty("is_private")
    private Boolean isPrivate;

    @JsonProperty("is_private")
    public boolean isPrivate() {
        return isPrivate != null && isPrivate;
    }

    @JsonIgnore
    public Boolean getIsPrivate() {
        return isPrivate;
    }

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("completor_user_id")
    private Long completorUserId;

    @JsonProperty("completor_user_name")
    private String completorUserName;

    @JsonProperty("account_id")
    private Long accountId;

    @JsonProperty("creator_color")
    private String creatorColor;

    @JsonProperty("completor_color")
    private String completorColor;

    @JsonProperty("done")
    public boolean isDone() {
        return done != null && done;
    }

    @JsonIgnore
    public Boolean getDone() {
        return done;
    }
}
