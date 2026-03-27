package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.TodoService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

/**
 * REST-контроллер для управления задачами.
 * Эндпоинты: создание, обновление, удаление, получение, отметка выполнения.
 */
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoRestController {

    private final TodoService todoService;
    private final TodoMapper todoMapper;
    private final UserService userService;

    /** Создание новой задачи */
    @PostMapping("/create")
    public ResponseEntity<TodoResponse> create(@Valid @RequestBody TodoRequest request) {
        TodoDto todoDto = todoMapper.toDto(request);
        TodoDto createdTodo = todoService.createTodo(todoDto);
        TodoResponse response = todoMapper.toResponse(createdTodo);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Обновление задачи по ID */
    @PutMapping("/{id}")
    public ResponseEntity<TodoResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody TodoRequest request,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        TodoDto todoDto = todoMapper.toDto(request);
        TodoDto updatedTodo = todoService.updateTodo(id, todoDto, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(updatedTodo);
        return ResponseEntity.ok(response);
    }

    /** Получение задачи по ID (с проверкой принадлежности к списку) */
    @GetMapping("/{id}")
    public ResponseEntity<TodoResponse> getTodoById(@PathVariable Long id,
                                                     @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        TodoDto todoDto = todoService.getTodoById(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Получение всех задач текущего пользователя */
    @GetMapping("/all")
    public ResponseEntity<List<TodoResponse>> getAllTodos(
            @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        List<TodoDto> todos = todoService.getAllTodos(currentUser.getId());
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Получение задач пользователя по его ID (с проверкой доступа) */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TodoResponse>> getTodosByUserId(@PathVariable Long userId,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        List<TodoDto> todos = todoService.getTodosByUserId(userId, currentUser.getId());
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Отметить задачу как выполненную (исполнитель — текущий пользователь из JWT) */
    @PatchMapping("/{id}/done")
    public ResponseEntity<TodoResponse> markAsDone(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        TodoDto todoDto = todoService.markAsDone(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Снять отметку выполнения задачи */
    @PatchMapping("/{id}/undone")
    public ResponseEntity<TodoResponse> markAsUndone(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        TodoDto todoDto = todoService.markAsUndone(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Удаление задачи по ID */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByEmail(userDetails.getUsername());
        todoService.deleteTodo(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

}
