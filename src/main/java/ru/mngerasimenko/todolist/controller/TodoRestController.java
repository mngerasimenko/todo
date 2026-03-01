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

@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoRestController {

    private final TodoService todoService;
    private final TodoMapper todoMapper;
    private final UserService userService;

    @PostMapping("/create")
    public ResponseEntity<TodoResponse> create(@Valid @RequestBody TodoRequest request) {
        TodoDto todoDto = todoMapper.toDto(request);
        TodoDto createdTodo = todoService.createTodo(todoDto);
        TodoResponse response = todoMapper.toResponse(createdTodo);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TodoResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody TodoRequest request) {
        TodoDto todoDto = todoMapper.toDto(request);
        TodoDto updatedTodo = todoService.updateTodo(id, todoDto);
        TodoResponse response = todoMapper.toResponse(updatedTodo);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TodoResponse> getTodoById(@PathVariable Long id) {
        TodoDto todoDto = todoService.getTodoById(id);
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<List<TodoResponse>> getAllTodos() {
        List<TodoDto> todos = todoService.getAllTodos();
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TodoResponse>> getTodosByUserId(@PathVariable Long userId) {
        List<TodoDto> todos = todoService.getTodosByUserId(userId);
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/{id}/done")
    public ResponseEntity<TodoResponse> markAsDone(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = userService.getUserByUserName(userDetails.getUsername());
        TodoDto todoDto = todoService.markAsDone(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/undone")
    public ResponseEntity<TodoResponse> markAsUndone(@PathVariable Long id) {
        TodoDto todoDto = todoService.markAsUndone(id);
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        todoService.deleteTodo(id);
        return ResponseEntity.noContent().build();
    }

}
