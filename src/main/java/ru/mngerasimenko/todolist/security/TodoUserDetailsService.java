package ru.mngerasimenko.todolist.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.AuthUserDto;
import ru.mngerasimenko.todolist.service.UserService;

/**
 * Сервис загрузки данных пользователя для Spring Security.
 * Используется при JWT-аутентификации.
 */
@Component
public class TodoUserDetailsService implements UserDetailsService {

    private final UserService userService;

    public TodoUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

        AuthUserDto authUser = userService.getUserByEmailForAuth(email);

        if (authUser == null) {
            throw new UsernameNotFoundException("User not found: " + email);
        }

        // Используем email как идентификатор в Spring Security.
        // Пароль из БД (уже BCrypt-хэш).
        // Роль — только USER; роли ADMIN/USER управляются через task_list_user (per-list).
        return org.springframework.security.core.userdetails.User
                .builder()
                .username(authUser.getEmail())
                .password(authUser.getPassword())
                .roles("USER")
                .build();
    }
}
