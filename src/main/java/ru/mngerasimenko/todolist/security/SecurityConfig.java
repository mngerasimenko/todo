package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.UserAuthService;
import ru.mngerasimenko.todolist.service.UserService;
import ru.mngerasimenko.todolist.view.LoginView;

import java.io.IOException;
import java.util.List;

@EnableWebSecurity
@Configuration
public class SecurityConfig extends VaadinWebSecurity {

    private final UserService userService;
    private final UserAuthService userAuthService;

    public SecurityConfig(UserService userService, UserAuthService userAuthService) {
        this.userService = userService;
        this.userAuthService = userAuthService;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.addFilterBefore(autoLoginFilter(), UsernamePasswordAuthenticationFilter.class);

        http.authorizeHttpRequests(auth ->
                auth.requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/images/*.png"))
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

    private OncePerRequestFilter autoLoginFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain)
                    throws ServletException, IOException {
                HttpSessionSecurityContextRepository securityContextRepository =
                        new HttpSessionSecurityContextRepository();

                SecurityContext context = securityContextRepository.loadContext(
                        new HttpRequestResponseHolder(request, response)
                );

                Authentication existingAuth = context.getAuthentication();

                if (existingAuth == null || !existingAuth.isAuthenticated() ||
                        (existingAuth.getPrincipal() instanceof String &&
                                existingAuth.getPrincipal().equals("anonymousUser"))) {

                    User authUser = userAuthService.getAuthUser(request);
                    if (authUser != null) {

                        UserDetails userDetails = org.springframework.security.core.userdetails.User
                                .withDefaultPasswordEncoder()
                                .username(authUser.getName())
                                .password(authUser.getPassword())
                                .roles("USER", "ADMIN")
                                .build();

                        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                        context.setAuthentication(auth);
                        securityContextRepository.saveContext(
                                context,
                                request,
                                response
                        );
                        System.out.println("Auto-login performed for user: " + authUser.getName());
                    }
                }

                filterChain.doFilter(request, response);
            }

            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                // Не применяем фильтр для статических ресурсов
                String path = request.getRequestURI();
                return path.startsWith("/VAADIN/") ||
                        path.startsWith("/frontend/") ||
                        path.startsWith("/images/") ||
                        path.contains("/login");
            }

        };
    }
}