package ru.mngerasimenko.todolist.model.status;

import java.util.Collections;
import java.util.List;
import ru.mngerasimenko.todolist.model.Todo;

public class StatusTodo extends Status {
    private List<Todo> todos;

    public StatusTodo(String status, List<Todo> todos) {
        super(status);
        this.todos = todos;
    }

    public StatusTodo(String status, Todo todo) {
        this(status,  Collections.singletonList(todo));
    }

    public List<Todo> getTodos() {
        return todos;
    }

    public void setTodos(List<Todo> todos) {
        this.todos = todos;
    }
}
