package ru.mngerasimenko.todolist.service;

import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TodoService {
    private final TodoRepository todoRepository;
    private final UserRepository userRepository;

    public TodoService(TodoRepository todoRepository, UserRepository userRepository) {
        this.todoRepository = todoRepository;
        this.userRepository = userRepository;
    }

    public Todo save(Todo todo) {
        Todo oldTodo;
        if (!todo.isNew()) {
            oldTodo = get(todo.getId(), todo.getAuthKey());
            if (oldTodo == null) {
                return null;
            } else {
                todo.setDateTime(oldTodo.getDateTime());
                todo.setDone(oldTodo.isDone());
            }
        } else {
            todo.setDateTime(LocalDateTime.now());
            todo.setDone(false);
        }
        todo.setUser(userRepository.getReferenceById(todo.getAuthKey()));
        return todoRepository.save(todo);
    }

    public Todo get(Long id, Long userId) {
        return todoRepository.findByIdAndUserId(id, userId);
    }

    public List<Todo> getAllNotDone(long userId) {
        return todoRepository.findAllByUserIdAndDoneOrderByIdDesc(userId, false);
    }

    public List<Todo> getAll(long userId) {
        return todoRepository.findAllByUserId(userId);
    }

    public List<Todo> getAllByFilter(long userId, String filter) {
        return todoRepository.findAllByUserIdAndNameContainingIgnoreCase(userId, filter);
    }

    public List<Todo> getAllDone(long userId) {
        return todoRepository.findAllByUserIdAndDoneOrderByIdDesc(userId, true);
    }

    public boolean delete(Todo todo) {
        int delete = todoRepository.deleteByUserIdAndId(todo.getAuthKey(), todo.getId());
        return delete == 1;
    }

    public Todo done(Todo todo) {
        Todo foundTodo = todoRepository.findByIdAndUserId(todo.getId(), todo.getAuthKey());
        if (foundTodo == null) {
            return null;
        }
        foundTodo.setDone(todo.isDone());
        return todoRepository.save(foundTodo);
    }
}
