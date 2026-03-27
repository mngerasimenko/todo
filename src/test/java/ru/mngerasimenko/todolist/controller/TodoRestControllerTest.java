package ru.mngerasimenko.todolist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.TodoService;
import ru.mngerasimenko.todolist.service.UserService;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TodoRestController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class TodoRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TodoService todoService;

    @MockitoBean
    private TodoMapper todoMapper;

    @MockitoBean
    private UserService userService;

    private TodoDto testTodoDto;
    private TodoResponse testTodoResponse;
    private TodoRequest testTodoRequest;

    @BeforeEach
    void setUp() {
        testTodoDto = new TodoDto();
        testTodoDto.setId(1L);
        testTodoDto.setName("Test Todo");
        testTodoDto.setDone(false);
        testTodoDto.setUserId(1L);
        testTodoDto.setListId(1L);
        testTodoDto.setCreatedAt(LocalDateTime.now());

        testTodoResponse = new TodoResponse();
        testTodoResponse.setId(1L);
        testTodoResponse.setName("Test Todo");
        testTodoResponse.setDone(false);
        testTodoResponse.setUserId(1L);
        testTodoResponse.setListId(1L);
        testTodoResponse.setCreatedAt(testTodoDto.getCreatedAt());

        testTodoRequest = new TodoRequest();
        testTodoRequest.setName("New Todo");
        testTodoRequest.setUserId(1L);
        testTodoRequest.setListId(1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void create_ValidRequest_ReturnsCreatedTodo() throws Exception {
        TodoDto createdDto = new TodoDto();
        createdDto.setId(2L);
        createdDto.setName("New Todo");
        createdDto.setDone(false);
        createdDto.setUserId(1L);
        createdDto.setListId(1L);
        createdDto.setCreatedAt(LocalDateTime.now());

        TodoResponse createdResponse = new TodoResponse();
        createdResponse.setId(2L);
        createdResponse.setName("New Todo");
        createdResponse.setDone(false);
        createdResponse.setUserId(1L);
        createdResponse.setListId(1L);
        createdResponse.setCreatedAt(createdDto.getCreatedAt());

        when(todoMapper.toDto(any(TodoRequest.class))).thenReturn(createdDto);
        when(todoService.createTodo(any(TodoDto.class))).thenReturn(createdDto);
        when(todoMapper.toResponse(any(TodoDto.class))).thenReturn(createdResponse);

        mockMvc.perform(post("/api/todos/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTodoRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.name").value("New Todo"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.user_id").value(1));

        verify(todoMapper, times(1)).toDto(any(TodoRequest.class));
        verify(todoService, times(1)).createTodo(any(TodoDto.class));
        verify(todoMapper, times(1)).toResponse(any(TodoDto.class));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void create_InvalidRequest_ReturnsBadRequest() throws Exception {
        TodoRequest invalidRequest = new TodoRequest();

        mockMvc.perform(post("/api/todos/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void create_UserNotFound_ReturnsNotFound() throws Exception {
        when(todoMapper.toDto(any(TodoRequest.class))).thenReturn(testTodoDto);
        when(todoService.createTodo(any(TodoDto.class)))
                .thenThrow(new UserNotFoundException("User not found with id: 999"));

        mockMvc.perform(post("/api/todos/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTodoRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void update_ValidRequest_ReturnsUpdatedTodo() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        TodoDto updatedDto = new TodoDto();
        updatedDto.setId(1L);
        updatedDto.setName("Updated Todo");
        updatedDto.setDone(true);
        updatedDto.setUserId(1L);
        updatedDto.setListId(1L);
        updatedDto.setCreatedAt(LocalDateTime.now());

        TodoResponse updatedResponse = new TodoResponse();
        updatedResponse.setId(1L);
        updatedResponse.setName("Updated Todo");
        updatedResponse.setDone(true);
        updatedResponse.setUserId(1L);
        updatedResponse.setListId(1L);
        updatedResponse.setCreatedAt(updatedDto.getCreatedAt());

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoMapper.toDto(any(TodoRequest.class))).thenReturn(updatedDto);
        when(todoService.updateTodo(eq(1L), any(TodoDto.class), eq(1L))).thenReturn(updatedDto);
        when(todoMapper.toResponse(any(TodoDto.class))).thenReturn(updatedResponse);

        mockMvc.perform(put("/api/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTodoRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Updated Todo"))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.user_id").value(1));

        verify(todoService, times(1)).updateTodo(eq(1L), any(TodoDto.class), eq(1L));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void update_TodoNotFound_ReturnsNotFound() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoMapper.toDto(any(TodoRequest.class))).thenReturn(testTodoDto);
        when(todoService.updateTodo(eq(999L), any(TodoDto.class), eq(1L)))
                .thenThrow(new TodoNotFoundException("Todo not found with id: 999"));

        mockMvc.perform(put("/api/todos/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTodoRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Todo not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getTodoById_ValidId_ReturnsTodo() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getTodoById(1L, 1L)).thenReturn(testTodoDto);
        when(todoMapper.toResponse(testTodoDto)).thenReturn(testTodoResponse);

        mockMvc.perform(get("/api/todos/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Test Todo"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.user_id").value(1));

        verify(todoService, times(1)).getTodoById(1L, 1L);
        verify(todoMapper, times(1)).toResponse(testTodoDto);
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getTodoById_NonExistentId_ReturnsNotFound() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getTodoById(999L, 1L))
                .thenThrow(new TodoNotFoundException("Todo not found with id: 999"));

        mockMvc.perform(get("/api/todos/999")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Todo not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getAllTodos_ReturnsListOfTodos() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        TodoDto todo1 = new TodoDto();
        todo1.setId(1L);
        todo1.setName("Todo 1");
        todo1.setDone(false);
        todo1.setUserId(1L);

        TodoDto todo2 = new TodoDto();
        todo2.setId(2L);
        todo2.setName("Todo 2");
        todo2.setDone(true);
        todo2.setUserId(1L);

        TodoResponse response1 = new TodoResponse();
        response1.setId(1L);
        response1.setName("Todo 1");
        response1.setDone(false);
        response1.setUserId(1L);

        TodoResponse response2 = new TodoResponse();
        response2.setId(2L);
        response2.setName("Todo 2");
        response2.setDone(true);
        response2.setUserId(1L);

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getAllTodos(1L)).thenReturn(Arrays.asList(todo1, todo2));
        when(todoMapper.toResponse(todo1)).thenReturn(response1);
        when(todoMapper.toResponse(todo2)).thenReturn(response2);

        mockMvc.perform(get("/api/todos/all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Todo 1"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("Todo 2"));

        verify(todoService, times(1)).getAllTodos(1L);
        verify(todoMapper, times(2)).toResponse(any(TodoDto.class));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getAllTodos_EmptyList_ReturnsEmptyArray() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getAllTodos(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/todos/all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getTodosByUserId_ValidUserId_ReturnsUserTodos() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        TodoDto todo1 = new TodoDto();
        todo1.setId(1L);
        todo1.setName("User Todo 1");
        todo1.setDone(false);
        todo1.setUserId(1L);

        TodoDto todo2 = new TodoDto();
        todo2.setId(2L);
        todo2.setName("User Todo 2");
        todo2.setDone(true);
        todo2.setUserId(1L);

        TodoResponse response1 = new TodoResponse();
        response1.setId(1L);
        response1.setName("User Todo 1");
        response1.setDone(false);
        response1.setUserId(1L);

        TodoResponse response2 = new TodoResponse();
        response2.setId(2L);
        response2.setName("User Todo 2");
        response2.setDone(true);
        response2.setUserId(1L);

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getTodosByUserId(1L, 1L)).thenReturn(Arrays.asList(todo1, todo2));
        when(todoMapper.toResponse(todo1)).thenReturn(response1);
        when(todoMapper.toResponse(todo2)).thenReturn(response2);

        mockMvc.perform(get("/api/todos/user/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("User Todo 1"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("User Todo 2"));

        verify(todoService, times(1)).getTodosByUserId(1L, 1L);
        verify(todoMapper, times(2)).toResponse(any(TodoDto.class));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void getTodosByUserId_NoTodos_ReturnsEmptyArray() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.getTodosByUserId(1L, 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/todos/user/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void create_MissingRequiredFields_ReturnsBadRequest() throws Exception {
        TodoRequest invalidRequest = new TodoRequest();

        mockMvc.perform(post("/api/todos/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void update_MissingRequiredFields_ReturnsBadRequest() throws Exception {
        TodoRequest invalidRequest = new TodoRequest();

        mockMvc.perform(put("/api/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void delete_ValidId_ReturnsNoContent() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        doNothing().when(todoService).deleteTodo(1L, 1L);

        mockMvc.perform(delete("/api/todos/1"))
                .andExpect(status().isNoContent());

        verify(todoService, times(1)).deleteTodo(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void delete_NonExistentId_ReturnsNotFound() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        doThrow(new TodoNotFoundException("Todo not found with id: 999"))
                .when(todoService).deleteTodo(999L, 1L);

        mockMvc.perform(delete("/api/todos/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Todo not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void markAsDone_ValidId_ReturnsMarkedTodo() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        TodoDto markedDto = new TodoDto();
        markedDto.setId(1L);
        markedDto.setName("Test Todo");
        markedDto.setDone(true);
        markedDto.setUserId(1L);

        TodoResponse markedResponse = new TodoResponse();
        markedResponse.setId(1L);
        markedResponse.setName("Test Todo");
        markedResponse.setDone(true);
        markedResponse.setUserId(1L);

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.markAsDone(1L, 1L)).thenReturn(markedDto);
        when(todoMapper.toResponse(markedDto)).thenReturn(markedResponse);

        mockMvc.perform(patch("/api/todos/1/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.done").value(true));

        verify(userService, times(1)).getUserByEmail("user@mail.ru");
        verify(todoService, times(1)).markAsDone(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void markAsDone_NonExistentId_ReturnsNotFound() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.markAsDone(999L, 1L))
                .thenThrow(new TodoNotFoundException("Todo not found with id: 999"));

        mockMvc.perform(patch("/api/todos/999/done"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Todo not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void markAsUndone_ValidId_ReturnsUnmarkedTodo() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        TodoDto unmarkedDto = new TodoDto();
        unmarkedDto.setId(1L);
        unmarkedDto.setName("Test Todo");
        unmarkedDto.setDone(false);
        unmarkedDto.setUserId(1L);

        TodoResponse unmarkedResponse = new TodoResponse();
        unmarkedResponse.setId(1L);
        unmarkedResponse.setName("Test Todo");
        unmarkedResponse.setDone(false);
        unmarkedResponse.setUserId(1L);

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.markAsUndone(1L, 1L)).thenReturn(unmarkedDto);
        when(todoMapper.toResponse(unmarkedDto)).thenReturn(unmarkedResponse);

        mockMvc.perform(patch("/api/todos/1/undone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.done").value(false));

        verify(todoService, times(1)).markAsUndone(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru", roles = {"USER"})
    void markAsUndone_NonExistentId_ReturnsNotFound() throws Exception {
        UserDto currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
        when(todoService.markAsUndone(999L, 1L))
                .thenThrow(new TodoNotFoundException("Todo not found with id: 999"));

        mockMvc.perform(patch("/api/todos/999/undone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Todo not found with id: 999"));
    }
}