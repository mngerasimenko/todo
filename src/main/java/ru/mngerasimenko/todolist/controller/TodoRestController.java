package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.TodoService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
@Validated
public class TodoRestController {

    private final TodoService todoService;
    private final TodoMapper todoMapper;

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
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TodoResponse>> getTodosByUserId(@PathVariable Long userId) {
        List<TodoDto> todos = todoService.getTodosByUserId(userId);
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

}
