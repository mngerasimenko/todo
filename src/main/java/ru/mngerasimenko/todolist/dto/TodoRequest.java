package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoRequest {

    @JsonProperty("id")
    private Long id;

    @NotBlank(message = "Todo name is required")
    @Size(min = 2, max = 120, message = "Todo name must be between 2 and 120 characters")
    private String name;

    @JsonProperty("date_time")
    @NotNull(message = "Date time is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dateTime;

    @NotNull(message = "Done status is required")
    private Boolean done;

    @JsonProperty("user_id")
    @NotNull(message = "User ID is required")
    private Long userId;

}
