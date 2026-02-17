package ru.mngerasimenko.todolist.config;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import ru.mngerasimenko.todolist.security.jwt.JwtAuthenticationFilter;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;

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

    /**
     * Создаёт настоящий JwtAuthenticationFilter для тестов.
     * Фильтр использует замокированные зависимости, но корректно пропускает запросы через filterChain.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService
    ) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    }
}
