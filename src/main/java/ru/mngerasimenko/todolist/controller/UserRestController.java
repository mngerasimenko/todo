package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.SubscriptionStatusResponse;
import ru.mngerasimenko.todolist.dto.UpdateColorsRequest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.service.SubscriptionService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

/**
 * REST-контроллер для управления пользователями.
 * Эндпоинты: CRUD пользователей, получение текущего пользователя, обновление цветов.
 * Операции изменения и удаления доступны только для собственного аккаунта.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserRestController {
    private final UserService userService;
    private final UserMapper userMapper;
    private final SubscriptionService subscriptionService;
    private final Validator validator;

    /** Получение текущего пользователя по JWT-токену */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        UserDto userDto = userService.getUserByEmail(userDetails.getUsername());
        UserResponse response = userMapper.toResponse(userDto);
        return ResponseEntity.ok(response);
    }

    /** Получение статуса подписки текущего пользователя */
    @GetMapping("/me/subscription")
    public ResponseEntity<SubscriptionStatusResponse> getSubscriptionStatus(
            @AuthenticationPrincipal UserDetails userDetails) {
        SubscriptionStatusResponse response = subscriptionService.getSubscriptionStatus(userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    /** Создание нового пользователя */
    @PostMapping("/create")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserDto userDto = userMapper.toDto(request);
        UserDto createdUser = userService.createUser(userDto);
        UserResponse response = userMapper.toResponse(createdUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Получение списка всех пользователей (только суперадмин) */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> showAll() {
        List<UserDto> users = userService.getAll();
        List<UserResponse> response = users.stream()
                .map(userMapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Получение пользователя по ID (только свой аккаунт) */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        assertOwner(id, userDetails);
        UserDto userDto = userService.getUserById(id);
        UserResponse response = userMapper.toResponse(userDto);
        return ResponseEntity.ok(response);
    }

    /** Обновление данных пользователя по ID (только свой аккаунт) */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @RequestBody UserRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        assertOwner(id, userDetails);
        validateRequest(request);
        UserDto userDto = userMapper.toDto(request);
        UserDto updatedUser = userService.updateUser(id, userDto);
        UserResponse response = userMapper.toResponse(updatedUser);
        return ResponseEntity.ok(response);
    }

    /** Обновить цвета задач пользователя (только свой аккаунт) */
    @PutMapping("/{id}/colors")
    public ResponseEntity<UserResponse> updateColors(
            @PathVariable Long id,
            @Valid @RequestBody UpdateColorsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        assertOwner(id, userDetails);
        UserDto updatedUser = userService.updateColors(id, request.getCreatedTaskColor(), request.getCompletedTaskColor());
        UserResponse response = userMapper.toResponse(updatedUser);
        return ResponseEntity.ok(response);
    }

    /** Удаление пользователя по ID (только свой аккаунт) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        assertOwner(id, userDetails);
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ручная валидация запроса (используется когда авторизация должна проверяться до валидации).
     */
    private <T> void validateRequest(T request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new jakarta.validation.ConstraintViolationException(violations);
        }
    }

    /**
     * Проверяет, что текущий пользователь является владельцем аккаунта.
     * Сравнивает ID из URL с ID текущего пользователя из JWT.
     */
    private void assertOwner(Long targetUserId, UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        if (!currentUser.getId().equals(targetUserId)) {
            throw new AccessDeniedException("Доступ запрещён: можно изменять только свой аккаунт");
        }
    }
}
