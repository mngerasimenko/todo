package ru.mngerasimenko.todolist.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.mngerasimenko.todolist.security.jwt.JwtAuthenticationFilter;

/**
 * Конфигурация безопасности для REST API с JWT аутентификацией.
 * Применяется к эндпоинтам /api/**
 */
@Configuration
@RequiredArgsConstructor
public class ApiSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Настраивает цепочку фильтров безопасности для REST API
     *
     * @param http конфигуратор HttpSecurity
     * @return настроенная цепочка фильтров
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        // Открытые эндпоинты (без аутентификации)
                        .requestMatchers(
                                "/api/status",
                                "/api/appName",
                                "/api/auth/**"
                        ).permitAll()
                        // Административные эндпоинты (требуют роль ADMIN)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Все остальные эндпоинты требуют аутентификации
                        .anyRequest().authenticated()
                )
                // Отключаем session-based аутентификацию для API (используем JWT)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Добавляем JWT фильтр перед UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Отключаем HTTP Basic (используем JWT)
                .httpBasic(AbstractHttpConfigurer::disable)
                // Отключаем CSRF для API (stateless)
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * Создаёт AuthenticationManager для использования в контроллерах
     *
     * @param config конфигурация аутентификации
     * @return AuthenticationManager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
