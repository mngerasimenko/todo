package ru.mngerasimenko.todolist.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP-фильтр для логирования запросов (метод, URI, статус, время выполнения).
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        // Продолжить обработку запроса
        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - startTime;

        String logMessage = String.format("request method: %s, request URI: %s, response status: %d, request processing time: %d ms",
                request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        logger.info(logMessage);
    }
}