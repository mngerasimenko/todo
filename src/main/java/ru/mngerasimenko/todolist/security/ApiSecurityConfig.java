package ru.mngerasimenko.todolist.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.mngerasimenko.todolist.security.jwt.JwtAuthenticationFilter;
import ru.mngerasimenko.todolist.settings.AppProperties;

import java.util.List;

/**
 * Конфигурация безопасности для REST API с JWT аутентификацией.
 * Применяется к эндпоинтам /api/**
 */
@Configuration
@RequiredArgsConstructor
public class ApiSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AppProperties appProperties;

    /**
     * Настраивает цепочку фильтров безопасности для REST API
     *
     * @param http конфигуратор HttpSecurity
     * @return настроенная цепочка фильтров
     */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Открытые эндпоинты (без аутентификации)
                        .requestMatchers(
                                "/api/status",
                                "/api/appName",
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/verify-email",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/v3/api-docs/**",
                                "/api/swagger-ui/**",
                                "/api/swagger-ui.html"
                        ).permitAll()
                        // Публичный GET для информации о приглашении
                        .requestMatchers(HttpMethod.GET, "/api/lists/invite/*").permitAll()
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
                // Добавляем rate limit фильтр перед JWT (т.е. rate limiting срабатывает первым)
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                // Возвращаем 401 вместо редиректа на /login для API эндпоинтов
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"error\":\"Unauthorized\"}");
                        })
                )
                // Отключаем HTTP Basic (используем JWT)
                .httpBasic(AbstractHttpConfigurer::disable)
                // Отключаем CSRF для API (stateless)
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(appProperties.getCorsOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
