package ru.mngerasimenko.todolist.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Collections;
import java.util.List;

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

        return org.springframework.security.core.userdetails.User
                .withDefaultPasswordEncoder()
                .username(userDto.getName())
                .password(userDto.getPassword())
                .roles("USER", "ADMIN")
                .build();
    }

    /**
     * Получение ролей/прав пользователя
     */
    private List<SimpleGrantedAuthority> getAuthorities(UserDto userDto) {

        // if (user.isAdmin()) {
        //     return List.of(
        //         new SimpleGrantedAuthority("ROLE_USER"),
        //         new SimpleGrantedAuthority("ROLE_ADMIN")
        //     );
        // }

        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
