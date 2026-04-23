package ru.mngerasimenko.todolist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO только для Spring Security auth-путей (JWT-filter + DaoAuthenticationProvider).
 * Содержит минимум полей, нужных для построения {@link org.springframework.security.core.userdetails.UserDetails}.
 * <p>
 * Почему отдельный DTO (не {@code UserDto}): {@code UserDto.password} помечен
 * {@code @JsonIgnore} (для защиты от утечки в HTTP-ответы), из-за чего
 * Jackson-сериализация в Redis-кэш теряет password — cache-hit отдавал
 * {@code UserDetails} с {@code password=null} и ломал login.
 * <p>
 * Здесь аннотаций Jackson нет, поле password всегда попадает в Redis.
 * Допустимо хранить BCrypt-хэш в кэше короткое время (TTL 60 сек) — BCrypt
 * не обратим, а сам Redis доступен только внутри docker-network.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUserDto {
    private String email;
    private String password;
}
