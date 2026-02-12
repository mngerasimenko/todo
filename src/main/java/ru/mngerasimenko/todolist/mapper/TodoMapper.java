package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.model.Todo;

@Component
public class TodoMapper {

    /**
     * Конвертация сущности в полный DTO
     */
    public TodoDto toDto(Todo todo) {
        if (todo == null) {
            return null;
        }

        return TodoDto.builder()
                .id(todo.getId())
                .name(todo.getName())
                .dateTime(todo.getDateTime())
                .done(todo.isDone())
                .userId(todo.getUser() != null ? todo.getUser().getId() : null)
                .userName(todo.getUser() != null ? todo.getUser().getName() : null)
                .userEmail(todo.getUser() != null ? todo.getUser().getEmail() : null)
                .build();
    }

    /**
     * Конвертация TodoDto в сущность
     */
    public Todo toEntity(TodoDto todoDto) {
        if (todoDto == null) {
            return null;
        }

        Todo todo = new Todo();
        todo.setId(todoDto.getId());
        todo.setName(todoDto.getName());
        todo.setDateTime(todoDto.getDateTime());
        todo.setDone(todoDto.getDone());
        todo.setUserId(todoDto.getUserId());
        return todo;
    }

    /**
     * Обновление существующей сущности из запроса
     */
    public void updateEntityFromDto(TodoDto todoDto, Todo todo) {
        if (todoDto == null || todo == null) {
            return;
        }

        todo.setName(todoDto.getName());
        todo.setDateTime(todoDto.getDateTime());
        if (todoDto.getDone() != null) {
            todo.setDone(todoDto.getDone());
        }
    }


    public TodoDto toDto(TodoRequest request) {
        return TodoDto.builder()
                .id(request.getId())
                .userId(request.getUserId())
                .name(request.getName())
                .done(request.getDone())
                .dateTime(request.getDateTime())
                .build();
    }

    public TodoResponse toResponse(TodoDto todoDto) {
        if (todoDto == null) {
            return null;
        }

        return TodoResponse.builder()
                .id(todoDto.getId())
                .name(todoDto.getName())
                .dateTime(todoDto.getDateTime())
                .done(todoDto.getDone())
                .userId(todoDto.getUserId())
                .userName(todoDto.getUserName())
                .createdAt(todoDto.getDateTime())
                .build();
    }
}
