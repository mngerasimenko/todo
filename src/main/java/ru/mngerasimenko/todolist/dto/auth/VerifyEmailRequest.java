package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса подтверждения email.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyEmailRequest {

    @NotBlank(message = "Token is required")
    private String token;
}
