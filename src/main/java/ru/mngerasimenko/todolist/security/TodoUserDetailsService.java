package ru.mngerasimenko.todolist.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.model.User;
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

        User user = userService.getUserByUserName(username);

        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        return org.springframework.security.core.userdetails.User
                .withDefaultPasswordEncoder()
                .username(user.getName())
                .password(user.getPassword())
                .roles("USER", "ADMIN")
                .build();
    }

    /**
     * Получение ролей/прав пользователя
     */
    private List<SimpleGrantedAuthority> getAuthorities(User user) {

        // if (user.isAdmin()) {
        //     return List.of(
        //         new SimpleGrantedAuthority("ROLE_USER"),
        //         new SimpleGrantedAuthority("ROLE_ADMIN")
        //     );
        // }

        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
