package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.UserService;
import ru.mngerasimenko.todolist.view.LoginView;


@EnableWebSecurity
@Configuration
public class SecurityConfig extends VaadinWebSecurity {
    private final UserService userService;

    public SecurityConfig(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth ->
                auth.requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "images/*.png"))
                        .permitAll());
        super.configure(http);
        setLoginView(http, LoginView.class);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        List<User> userList = userService.getAll();
        for (User user : userList) {
            UserDetails userDetails = org.springframework.security.core.userdetails.User
                    .withDefaultPasswordEncoder()
                    .username(user.getName())
                    .password(user.getPassword())
                    .roles("USER", "ADMIN")
                    .build();
            manager.createUser(userDetails);
        }
        return manager;
    }

}
