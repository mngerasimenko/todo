package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;

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

    @Email(message = "{validation.email.format}")
    @NotBlank(message = "{validation.email.required}")
    @Size(max = EmailValidation.MAX_LENGTH, message = EmailValidation.MAX_LENGTH_MESSAGE)
    private String email;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 5, max = 128, message = "{validation.password.size}")
    private String password;

    @NotBlank(message = "{validation.name.required}")
    @Pattern(regexp = "^[^<>]*$", message = "{validation.name.invalid-characters}")
    private String name;
}
