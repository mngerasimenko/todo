package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.TodoDto;

import java.util.List;

public interface TodoService {

    TodoDto createTodo(TodoDto todoDto);

    TodoDto updateTodo(Long id, TodoDto todoDto);

    TodoDto getTodoById(Long id);

    List<TodoDto> getAllTodos();

    List<TodoDto> getTodosByUserId(Long userId);

    List<TodoDto> getActiveTodosByUserId(Long userId);

    List<TodoDto> getCompletedTodosByUserId(Long userId);

    void deleteTodo(Long id);

    void deleteTodosByUserId(Long userId);

    TodoDto markAsDone(Long id);

    TodoDto markAsDone(Long id, Long completorUserId);

    TodoDto markAsUndone(Long id);

    List<TodoDto> getFilteredTodosByUserId(Long id, String filter);
}
