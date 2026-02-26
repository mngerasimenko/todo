package ru.mngerasimenko.todolist.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
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
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        UserDto userDto = userService.getUserByUserName(username);

        if (userDto == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        // Используем пароль из БД (уже BCrypt-хэш).
        // Роль — только USER; роли ADMIN/USER управляются через task_list_user (per-list).
        return org.springframework.security.core.userdetails.User
                .builder()
                .username(userDto.getName())
                .password(userDto.getPassword())
                .roles("USER")
                .build();
    }
}
