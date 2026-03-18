package ru.mngerasimenko.todolist.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Глобальный обработчик исключений для REST API.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Обрабатывает исключение «пользователь не найден» (HTTP 404).
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException ex) {
        log.warn("USER Not Found: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.NOT_FOUND, "USER Not Found", ex.getMessage());
    }

    /**
     * Обрабатывает исключение «задача не найдена» (HTTP 404).
     */
    @ExceptionHandler(TodoNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTodoNotFound(TodoNotFoundException ex) {
        log.warn("TODO Not Found: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.NOT_FOUND, "TODO Not Found", ex.getMessage());
    }

    /**
     * Обрабатывает некорректные аргументы запроса (HTTP 400).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad Request: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    /**
     * Обрабатывает ошибки валидации полей запроса (HTTP 400).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        log.warn("Validation error: {}", ex.getMessage());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    /**
     * Обрабатывает несоответствие типов параметров запроса (HTTP 400).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        log.warn("Type mismatch error: {}", ex.getMessage());

        Map<String, String> error = new HashMap<>();
        error.put("error", "Invalid parameter type for field: " + ex.getName());
        error.put("expectedType", ex.getRequiredType() != null ?
                ex.getRequiredType().getSimpleName() : "unknown");


        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Обрабатывает ошибки парсинга JSON в теле запроса (HTTP 400).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParseError(
            HttpMessageNotReadableException ex) {

        log.warn("JSON parse error: {}", ex.getMessage());

        return createErrorResponse(HttpStatus.BAD_REQUEST, "Invalid JSON format in request body", ex.getMessage());
    }

    /**
     * Нарушение целостности данных (FK constraint, unique и т.п.) при commit транзакции (HTTP 409).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Data integrity violation: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.CONFLICT, "Conflict",
                "Нарушение целостности данных. Операция отменена.");
    }

    /**
     * Конкурентное обновление одной записи двумя потоками (оптимистичная блокировка).
     * Клиент должен повторить запрос с актуальными данными.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Конфликт оптимистичной блокировки: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.CONFLICT, "Conflict",
                "Данные были изменены другим пользователем. Пожалуйста, повторите запрос.");
    }

    /**
     * Обрабатывает истёкший или невалидный токен верификации/сброса пароля (HTTP 400).
     */
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleTokenExpired(TokenExpiredException ex) {
        log.warn("Token expired: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    /**
     * Обрабатывает отказ в доступе — пользователь пытается изменить чужие данные (HTTP 403).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
    }

    /**
     * Обрабатывает ошибки аутентификации (HTTP 401).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return createErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
    }

    /**
     * Обрабатывает обращение к несуществующему статическому ресурсу (HTTP 404).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("Static resource not found: {}", ex.getResourcePath());
        return createErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * Обрабатывает все непредвиденные исключения (HTTP 500).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {

        log.error("Unexpected error: {}", ex.getMessage(), ex);

        return createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ex.getMessage());
    }

    /**
     * Обрабатывает нарушения ограничений валидации (HTTP 400).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(
            ConstraintViolationException ex) {

        log.warn("Constraint violation: {}", ex.getMessage());

        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String fieldName = violation.getPropertyPath().toString();
            String errorMessage = violation.getMessage();
            errors.put(fieldName, errorMessage);
        });

        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> createErrorResponse(
            HttpStatus status, String error, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", status.value());
        response.put("error", error);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
