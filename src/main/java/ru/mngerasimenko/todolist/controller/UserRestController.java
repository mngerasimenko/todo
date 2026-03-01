package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.UpdateColorsRequest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserRestController {
    private final UserService userService;
    private final UserMapper userMapper;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        UserDto userDto = userService.getUserByUserName(userDetails.getUsername());
        UserResponse response = userMapper.toResponse(userDto);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/create")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserDto userDto = userMapper.toDto(request);
        UserDto createdUser = userService.createUser(userDto);
        UserResponse response = userMapper.toResponse(createdUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/all")
    public ResponseEntity<List<UserResponse>> showAll() {
        List<UserDto> users = userService.getAll();
        List<UserResponse> response = users.stream()
                .map(userMapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        UserDto userDto = userService.getUserById(id);
        UserResponse response = userMapper.toResponse(userDto);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {
        UserDto userDto = userMapper.toDto(request);
        UserDto updatedUser = userService.updateUser(id, userDto);
        UserResponse response = userMapper.toResponse(updatedUser);
        return ResponseEntity.ok(response);
    }

    /**
     * Обновить цвета задач пользователя.
     */
    @PutMapping("/{id}/colors")
    public ResponseEntity<UserResponse> updateColors(
            @PathVariable Long id,
            @Valid @RequestBody UpdateColorsRequest request) {
        UserDto updatedUser = userService.updateColors(id, request.getCreatedTaskColor(), request.getCompletedTaskColor());
        UserResponse response = userMapper.toResponse(updatedUser);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteUser(@PathVariable int id) {
        userService.delete(id);
        return ResponseEntity.ok("User with ID = " + id + " was deleted");
    }
}
