package ru.mngerasimenko.todolist.utils;

import io.micrometer.common.util.StringUtils;
import ru.mngerasimenko.todolist.model.Todo;

public class ValidateUtils {
    private ValidateUtils() {
    }

    public static boolean isAuthValid(Todo todo) {
        return todo.getAuthKey() != null;
    }

    public static boolean isIdValid(Todo todo) {
        return todo.getId() != null;
    }

    public static boolean isNameValid(Todo todo) {
        return !StringUtils.isBlank(todo.getName());
    }

    public static boolean isDoneValid(Todo todo) {
        return todo.isDone() != null;
    }
}
