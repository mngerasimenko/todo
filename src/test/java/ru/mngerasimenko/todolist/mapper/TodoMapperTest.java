package ru.mngerasimenko.todolist.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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

        TaskList taskList = new TaskList("TestList", user);
        taskList.setId(1L);

        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Test Todo");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 30));
        todo.setUser(user);
        todo.setTaskList(taskList);

        TodoDto result = todoMapper.toDto(todo);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isFalse();
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getUserName()).isEqualTo("testuser");
        assertThat(result.getUserEmail()).isEqualTo("test@mail.ru");
        assertThat(result.getListId()).isEqualTo(1L);
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
                .createdAt(LocalDateTime.of(2024, 1, 15, 10, 30))
                .userId(1L)
                .build();

        Todo result = todoMapper.toEntity(todoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isTrue();
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
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
                .build();

        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Old Name");
        todo.setDone(false);
        todo.setCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 30));

        todoMapper.updateEntityFromDto(todoDto, todo);

        assertThat(todo.getName()).isEqualTo("Updated Todo");
        assertThat(todo.isDone()).isTrue();
        // createdAt не должен изменяться при updateEntityFromDto
        assertThat(todo.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
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
        request.setListId(2L);
        request.setPrivate(false);

        TodoDto result = todoMapper.toDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("New Todo");
        assertThat(result.isDone()).isFalse();
        assertThat(result.getListId()).isEqualTo(2L);
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
    }

    @Test
    void toResponse_WithValidTodoDto_ReturnsTodoResponse() {
        TodoDto todoDto = TodoDto.builder()
                .id(1L)
                .name("Test Todo")
                .done(true)
                .createdAt(LocalDateTime.of(2024, 1, 15, 10, 30))
                .userId(1L)
                .userName("testuser")
                .userEmail("test@mail.ru")
                .listId(1L)
                .build();

        TodoResponse result = todoMapper.toResponse(todoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Todo");
        assertThat(result.isDone()).isTrue();
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getUserName()).isEqualTo("testuser");
        assertThat(result.getListId()).isEqualTo(1L);
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
        originalTodo.setCreatedAt(LocalDateTime.now());
        originalTodo.setUser(user);

        TodoDto dto = todoMapper.toDto(originalTodo);
        Todo convertedBack = todoMapper.toEntity(dto);

        assertThat(convertedBack.getId()).isEqualTo(originalTodo.getId());
        assertThat(convertedBack.getName()).isEqualTo(originalTodo.getName());
        assertThat(convertedBack.isDone()).isEqualTo(originalTodo.isDone());
        assertThat(convertedBack.getUserId()).isEqualTo(originalTodo.getUserId());
        assertThat(convertedBack.getUser()).isNull();
    }

    @Test
    void toDto_WithCompletorUser_MapsCompletorFields() {
        User creator = new User();
        creator.setId(1L);
        creator.setName("creator");
        creator.setEmail("creator@mail.ru");

        User completor = new User();
        completor.setId(2L);
        completor.setName("completor");
        completor.setEmail("completor@mail.ru");

        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Completed Todo");
        todo.setDone(true);
        todo.setCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 0));
        todo.setCompletedAt(LocalDateTime.of(2024, 1, 15, 12, 0));
        todo.setUser(creator);
        todo.setCompletorUser(completor);

        TodoDto result = todoMapper.toDto(todo);

        assertThat(result.getCompletorUserId()).isEqualTo(2L);
        assertThat(result.getCompletorUserName()).isEqualTo("completor");
        assertThat(result.getCompletedAt()).isEqualTo(LocalDateTime.of(2024, 1, 15, 12, 0));
    }

    @Test
    void toResponse_MapsDueFields() {
        TodoDto dto = TodoDto.builder()
                .id(1L).name("Полить теплицу").done(false)
                .createdAt(LocalDateTime.now())
                .dueDate(LocalDate.of(2026, 7, 31))
                .dueTime(LocalTime.of(18, 0))
                .dueTimezone("Asia/Novosibirsk")
                .remindBeforeMinutes(1440)
                .reminderScope(ReminderScope.ALL)
                .build();

        TodoResponse response = todoMapper.toResponse(dto);

        assertThat(response.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(response.getDueTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(response.getDueTimezone()).isEqualTo("Asia/Novosibirsk");
        assertThat(response.getRemindBeforeMinutes()).isEqualTo(1440);
        assertThat(response.getReminderScope()).isEqualTo(ReminderScope.ALL);
    }

    @Test
    void toDto_FromRequest_MapsDueFields() {
        TodoRequest request = new TodoRequest();
        request.setName("Позвонить в клинику");
        request.setDueDate(LocalDate.of(2026, 8, 13));
        request.setDueTime(LocalTime.of(18, 0));
        request.setDueTimezone("Europe/Moscow");
        request.setRemindBeforeMinutes(60);
        request.setReminderScope(ReminderScope.ALL);

        TodoDto dto = todoMapper.toDto(request);

        assertThat(dto.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(dto.getRemindBeforeMinutes()).isEqualTo(60);
        assertThat(dto.getReminderScope()).isEqualTo(ReminderScope.ALL);
    }

    @Test
    void updateEntityFromDto_MapsDueFieldsToEntity() {
        Todo todo = new Todo();
        TodoDto dto = TodoDto.builder()
                .name("Корм заказать").done(false)
                .dueDate(LocalDate.of(2026, 7, 31))
                .dueTime(LocalTime.of(9, 0))
                .dueTimezone("Europe/Moscow")
                .remindBeforeMinutes(0)
                .reminderScope(ReminderScope.SELF)
                .build();

        todoMapper.updateEntityFromDto(dto, todo);

        assertThat(todo.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(todo.getDueTimezone()).isEqualTo("Europe/Moscow");
    }
}
