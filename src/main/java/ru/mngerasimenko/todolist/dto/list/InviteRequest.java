package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;

/**
 * DTO запроса на создание приглашения в список.
 * Если email указан — отправляется письмо с приглашением.
 * Если null — возвращается только ссылка для копирования.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteRequest {

    @Email(message = "Invalid email format")
    @Size(max = EmailValidation.MAX_LENGTH, message = EmailValidation.MAX_LENGTH_MESSAGE)
    private String email;

    public void setEmail(String email) {
        this.email = email != null ? email.trim().toLowerCase() : null;
    }
}
