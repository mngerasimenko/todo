package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO входящего запроса на создание/обновление пользователя.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("auth_id")
    @Size(max = 128)
    private String authId;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    @Size(max = 128)
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 5, max = 128, message = "Password must be between 5 and 128 characters")
    private String password;

    @NotBlank(message = "Name is required")
    private String name;
}
