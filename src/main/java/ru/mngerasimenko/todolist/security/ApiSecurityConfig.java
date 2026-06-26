package ru.mngerasimenko.todolist.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
@EnableMethodSecurity
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
                                "/api/swagger-ui.html",
                                "/api/track/**",
                                "/api/users/unsubscribe-reminder"
                        ).permitAll()
                        // Публичный GET для информации о приглашении
                        .requestMatchers(HttpMethod.GET, "/api/lists/invite/*").permitAll()
                        // Публичный GET глобального словаря подсказок (Server R-6).
                        // Доступен гостям без JWT, чтобы закрывать «холодный старт».
                        .requestMatchers(HttpMethod.GET, "/api/suggestions").permitAll()
                        // Публичный GET bulk-выгрузки словаря для локального кэша клиента (Server R-7).
                        // Отдельный exact-matcher: точечный /api/suggestions выше его не покрывает.
                        .requestMatchers(HttpMethod.GET, "/api/suggestions/all").permitAll()
                        // Административные эндпоинты: аутентификация обязательна,
                        // проверка супер-админа идёт через @PreAuthorize на контроллере
                        .requestMatchers("/api/admin/**").authenticated()
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
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // Скрываем существование /api/admin — возвращаем 404 вместо 403
                            if (request.getRequestURI().startsWith("/api/admin")) {
                                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                                response.setContentType("application/json");
                                response.setCharacterEncoding("UTF-8");
                                response.getWriter().write("{\"error\":\"Not Found\"}");
                            } else {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.setCharacterEncoding("UTF-8");
                                response.getWriter().write("{\"error\":\"Forbidden\"}");
                            }
                        })
                )
                // Отключаем HTTP Basic (используем JWT)
                .httpBasic(AbstractHttpConfigurer::disable)
                // Отключаем CSRF для API (stateless)
                .csrf(AbstractHttpConfigurer::disable)
                // Отключаем заголовки безопасности, которые уже устанавливает nginx,
                // чтобы избежать дублирования (HSTS, X-Content-Type-Options, X-Frame-Options)
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts.disable())
                        .contentTypeOptions(cto -> cto.disable())
                        .frameOptions(fo -> fo.disable())
                );

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
