package ru.mngerasimenko.todolist.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TodoMapperTest {
    private TodoMapper todoMapper;

    @BeforeEach
    void setUp() {
        todoMapper = new TodoMapper();
    }

    @Test
    void toDto_WithValidTodo_ReturnsTodoDto() {
        User user = new User();
        user.setId(1L);
        user.setName("testuser");
        user.setEmail("test@mail.ru");

        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Test Todo");
        todo.setDone(false);
        todo.setDateTime(LocalDateTime.of(2024, 1, 15, 10, 30));
        todo.setUser(user);

        TodoDto result = todoMapper.toDto(todo);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isFalse();
        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getUserName()).isEqualTo("testuser");
        assertThat(result.getUserEmail()).isEqualTo("test@mail.ru");
    }

    @Test
    void toDto_WithNullTodo_ReturnsNull() {
        TodoDto result = todoMapper.toDto((Todo) null);

        assertThat(result).isNull();
    }

    @Test
    void toDto_WithTodoWithoutUser_ReturnsDtoWithNullUserFields() {
        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Test Todo");
        todo.setUser(null);

        TodoDto result = todoMapper.toDto(todo);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isNull();
        assertThat(result.getUserName()).isNull();
        assertThat(result.getUserEmail()).isNull();
    }

    @Test
    void toEntity_WithValidTodoDto_ReturnsTodoEntity() {
        TodoDto todoDto = TodoDto.builder()
                .id(1L)
                .name("Test Todo")
                .done(true)
                .dateTime(LocalDateTime.of(2024, 1, 15, 10, 30))
                .userId(1L)
                .build();

        Todo result = todoMapper.toEntity(todoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isTrue();
        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
        assertThat(result.getUserId()).isEqualTo(1L);
    }

    @Test
    void toEntity_WithNullTodoDto_ReturnsNull() {
        Todo result = todoMapper.toEntity(null);

        assertThat(result).isNull();
    }

    @Test
    void updateEntityFromDto_WithValidDtoAndEntity_UpdatesEntityFields() {
        TodoDto todoDto = TodoDto.builder()
                .name("Updated Todo")
                .done(true)
                .dateTime(LocalDateTime.of(2024, 1, 16, 11, 45))
                .build();

        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Old Name");
        todo.setDone(false);
        todo.setDateTime(LocalDateTime.of(2024, 1, 15, 10, 30));

        todoMapper.updateEntityFromDto(todoDto, todo);

        assertThat(todo.getName()).isEqualTo("Updated Todo");
        assertThat(todo.isDone()).isTrue();
        assertThat(todo.getDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 16, 11, 45));
        assertThat(todo.getId()).isEqualTo(1L);
    }

    @Test
    void updateEntityFromDto_WithNullDoneValue_DoesNotUpdateDoneField() {
        TodoDto todoDto = TodoDto.builder()
                .name("Updated Todo")
                .done(null)
                .build();

        Todo todo = new Todo();
        todo.setDone(false);

        todoMapper.updateEntityFromDto(todoDto, todo);

        assertThat(todo.getName()).isEqualTo("Updated Todo");
        assertThat(todo.isDone()).isFalse();
    }

    @Test
    void updateEntityFromDto_WithNullDto_DoesNothing() {
        Todo todo = new Todo();
        todo.setName("Original");

        todoMapper.updateEntityFromDto(null, todo);

        assertThat(todo.getName()).isEqualTo("Original");
    }

    @Test
    void updateEntityFromDto_WithNullEntity_DoesNothing() {
        TodoDto todoDto = TodoDto.builder()
                .name("Updated")
                .build();

        todoMapper.updateEntityFromDto(todoDto, null);
    }

    @Test
    void toDto_WithValidTodoRequest_ReturnsTodoDto() {
        TodoRequest request = new TodoRequest();
        request.setId(1L);
        request.setUserId(1L);
        request.setName("New Todo");
        request.setDone(false);
        request.setDateTime(LocalDateTime.of(2024, 1, 15, 10, 30));

        TodoDto result = todoMapper.toDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("New Todo");
        assertThat(result.isDone()).isFalse();
        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
    }

    @Test
    void toDto_WithNullFieldsInRequest_ReturnsDtoWithNullFields() {
        TodoRequest request = new TodoRequest();
        request.setName("Todo");

        TodoDto result = todoMapper.toDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Todo");
        assertThat(result.getId()).isNull();
        assertThat(result.getUserId()).isNull();
        assertThat(result.getDone()).isNull();
        assertThat(result.getDateTime()).isNull();
    }

    @Test
    void toResponse_WithValidTodoDto_ReturnsTodoResponse() {
        TodoDto todoDto = TodoDto.builder()
                .id(1L)
                .name("Test Todo")
                .done(true)
                .dateTime(LocalDateTime.of(2024, 1, 15, 10, 30))
                .userId(1L)
                .userName("testuser")
                .userEmail("test@mail.ru")
                .build();

        TodoResponse result = todoMapper.toResponse(todoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isTrue();
        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getUserName()).isEqualTo("testuser");
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
    }

    @Test
    void toResponse_WithNullTodoDto_ReturnsNull() {
        TodoResponse result = todoMapper.toResponse(null);

        assertThat(result).isNull();
    }

    @Test
    void toResponse_WithNullFieldsInDto_ReturnsResponseWithNullFields() {
        TodoDto todoDto = TodoDto.builder()
                .id(1L)
                .name("Todo")
                .build();

        TodoResponse result = todoMapper.toResponse(todoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Todo");
        assertThat(result.getUserId()).isNull();
        assertThat(result.getUserName()).isNull();
        assertThat(result.getCreatedAt()).isNull();
    }

    @Test
    void toDto_MaintainsConsistencyBetweenEntityAndDto() {
        User user = new User();
        user.setId(2L);
        user.setName("john");
        user.setEmail("john@mail.ru");

        Todo originalTodo = new Todo();
        originalTodo.setId(10L);
        originalTodo.setName("Consistency Test");
        originalTodo.setDone(true);
        originalTodo.setDateTime(LocalDateTime.now());
        originalTodo.setUser(user);

        TodoDto dto = todoMapper.toDto(originalTodo);
        Todo convertedBack = todoMapper.toEntity(dto);

        assertThat(convertedBack.getId()).isEqualTo(originalTodo.getId());
        assertThat(convertedBack.getName()).isEqualTo(originalTodo.getName());
        assertThat(convertedBack.isDone()).isEqualTo(originalTodo.isDone());
        assertThat(convertedBack.getUserId()).isEqualTo(originalTodo.getUserId());
        assertThat(convertedBack.getUser()).isNull();
    }
}