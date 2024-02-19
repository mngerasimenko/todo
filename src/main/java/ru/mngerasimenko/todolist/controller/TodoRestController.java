package ru.mngerasimenko.todolist.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.status.StatusMessage;
import ru.mngerasimenko.todolist.service.TodoService;
import ru.mngerasimenko.todolist.model.status.Status;
import ru.mngerasimenko.todolist.model.status.StatusTodo;
import ru.mngerasimenko.todolist.utils.ValidateUtils;

import static ru.mngerasimenko.todolist.settings.Constants.*;

@RestController
@RequestMapping("/api")
public class TodoRestController {
    private final TodoService todoService;

    public TodoRestController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping("/todos")
    public Status getAllNotDone(@RequestParam("authKey") Long userId) {
        return new StatusTodo(LOADED, todoService.getAllNotDone(userId));
    }

    @GetMapping("/done-todos")
    public Status getAllDone(@RequestParam("authKey") Long userId) {
        return new StatusTodo(LOADED, todoService.getAllDone(userId));
    }

    @PutMapping("/done")
    public Status done(@RequestBody Todo todo) {
        if (!ValidateUtils.isAuthValid(todo)) {
            return new Status(EMPTY_AUTHKEY);
        }
        if (!ValidateUtils.isIdValid(todo)) {
            return new Status(EMPTY_TODO_ID);
        }
        if (!ValidateUtils.isDoneValid(todo)) {
            return new Status(EMPTY_DONE);
        }

        Todo modifiedTodo = todoService.done(todo);
        if (modifiedTodo == null) {
            return new Status(BAD_REQUEST);
        }
        return new StatusTodo(MODIFIED, modifiedTodo);
    }

    @PostMapping("/todos")
    public Status addNew(@RequestBody Todo todo) {
        if (!ValidateUtils.isAuthValid(todo)) {
            return new Status(EMPTY_AUTHKEY);
        }
        if (!ValidateUtils.isTitleValid(todo)) {
            return new Status(EMPTY_TITLE);
        }
        todo.setId(null);
        Todo savedTodo = todoService.save(todo);
        if (savedTodo == null) {
            return new Status(BAD_REQUEST);
        }
        return new StatusTodo(CREATED, savedTodo);
    }

    @PutMapping("/todo-edit")
    public Status update(@RequestBody Todo todo) {
        if (!ValidateUtils.isAuthValid(todo)) {
            return new Status(EMPTY_AUTHKEY);
        }
        if (!ValidateUtils.isIdValid(todo)) {
            return new Status(EMPTY_TODO_ID);
        }
        if (!ValidateUtils.isTitleValid(todo)) {
            return new Status(EMPTY_TITLE);
        }
        Todo updateTodo = todoService.save(todo);
        if (updateTodo == null) {
            return new Status(BAD_REQUEST);
        }
        return new StatusTodo(MODIFIED, updateTodo);
    }

    @DeleteMapping("/todos")
    public Status delete(@RequestBody Todo todo) {
        if (!ValidateUtils.isAuthValid(todo)) {
            return new Status(EMPTY_AUTHKEY);
        }
        if (!ValidateUtils.isIdValid(todo)) {
            return new Status(EMPTY_TODO_ID);
        }
        if (todoService.delete(todo)) {
            return new StatusMessage(DELETED, "Todo with id " + todo.getId() + " was delete successfully");
        }
        return new StatusMessage(FAIL, "Todo with id " + todo.getId() + " was NOT deleted");
    }

}
