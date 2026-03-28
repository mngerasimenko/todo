package ru.mngerasimenko.todolist.config;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.security.RateLimitFilter;
import ru.mngerasimenko.todolist.security.RateLimitProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtAuthenticationFilter;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.settings.AppProperties;

/**
 * Тестовая конфигурация для создания моков JWT компонентов в тестах.
 * Создаёт настоящий JwtAuthenticationFilter с замокированными зависимостями,
 * чтобы фильтр корректно пропускал запросы в тестах.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        return Mockito.mock(JwtTokenProvider.class);
    }

    @Bean
    public JwtProperties jwtProperties() {
        return Mockito.mock(JwtProperties.class);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return Mockito.mock(UserDetailsService.class);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AppProperties appProperties() {
        return new AppProperties();
    }

    @Bean
    public TokenBlacklistService tokenBlacklistService() {
        return Mockito.mock(TokenBlacklistService.class);
    }

    /**
     * Создаёт настоящий JwtAuthenticationFilter для тестов.
     * Фильтр использует замокированные зависимости, но корректно пропускает запросы через filterChain.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService,
            TokenBlacklistService tokenBlacklistService
    ) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, tokenBlacklistService);
    }

    /**
     * Создаёт RateLimitFilter с разрешительными лимитами для тестов.
     */
    @Bean
    public RateLimitFilter rateLimitFilter() {
        RateLimitProperties props = new RateLimitProperties();
        props.setLogin(new RateLimitProperties.EndpointLimit(1000, 1));
        props.setRegister(new RateLimitProperties.EndpointLimit(1000, 1));
        props.setRefresh(new RateLimitProperties.EndpointLimit(1000, 1));
        props.setGeneral(new RateLimitProperties.EndpointLimit(1000, 1));
        props.setChangeEmail(new RateLimitProperties.EndpointLimit(1000, 1));
        return new RateLimitFilter(props);
    }
}
