package ru.mngerasimenko.todolist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.list.CreateListRequest;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.TaskListService;
import ru.mngerasimenko.todolist.service.UserService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskListController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class TaskListControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskListService taskListService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TodoMapper todoMapper;

    private UserDto currentUser;
    private ListResponse testListResponse;

    @BeforeEach
    void setUp() {
        currentUser = new UserDto();
        currentUser.setId(1L);
        currentUser.setName("user");

        testListResponse = ListResponse.builder()
                .id(1L)
                .name("Тестовый список")
                .role("ADMIN")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0))
                .build();

        when(userService.getUserByUserName("user")).thenReturn(currentUser);
    }

    // === POST /api/lists — создание списка ===

    @Test
    @WithMockUser(username = "user")
    void createList_ValidRequest_ReturnsCreated() throws Exception {
        CreateListRequest request = CreateListRequest.builder()
                .name("Новый список")
                .build();

        when(taskListService.createList("Новый список", 1L))
                .thenReturn(testListResponse);

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Тестовый список"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        verify(taskListService).createList("Новый список", 1L);
    }

    @Test
    @WithMockUser(username = "user")
    void createList_DuplicateName_ReturnsBadRequest() throws Exception {
        CreateListRequest request = CreateListRequest.builder()
                .name("Существующий")
                .build();

        when(taskListService.createList("Существующий", 1L))
                .thenThrow(new IllegalArgumentException("У вас уже есть список с названием 'Существующий'"));

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("У вас уже есть список с названием 'Существующий'"));
    }

    @Test
    @WithMockUser(username = "user")
    void createList_BlankName_ReturnsBadRequest() throws Exception {
        CreateListRequest request = CreateListRequest.builder()
                .name("")
                .build();

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user")
    void createList_NameTooShort_ReturnsBadRequest() throws Exception {
        CreateListRequest request = CreateListRequest.builder()
                .name("A")
                .build();

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user")
    void createList_MissingBody_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    void createList_Unauthenticated_ReturnsUnauthorized() throws Exception {
        CreateListRequest request = CreateListRequest.builder()
                .name("Список")
                .build();

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(taskListService);
    }

    // === GET /api/lists — мои списки ===

    @Test
    @WithMockUser(username = "user")
    void getMyLists_ReturnsList() throws Exception {
        ListResponse list1 = ListResponse.builder()
                .id(1L).name("Список 1").role("ADMIN").build();
        ListResponse list2 = ListResponse.builder()
                .id(2L).name("Список 2").role("USER").build();

        when(taskListService.getListsByUserId(1L))
                .thenReturn(List.of(list1, list2));

        mockMvc.perform(get("/api/lists"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Список 1"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].role").value("USER"));

        verify(taskListService).getListsByUserId(1L);
    }

    @Test
    @WithMockUser(username = "user")
    void getMyLists_Empty_ReturnsEmptyArray() throws Exception {
        when(taskListService.getListsByUserId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/lists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getMyLists_Unauthenticated_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/lists"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(taskListService);
    }

    // === GET /api/lists/{id}/members — участники списка ===

    @Test
    @WithMockUser(username = "user")
    void getMembers_ValidList_ReturnsMembers() throws Exception {
        ListMemberResponse member1 = ListMemberResponse.builder()
                .userId(1L).userName("user").role("ADMIN").joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0)).build();
        ListMemberResponse member2 = ListMemberResponse.builder()
                .userId(2L).userName("user2").role("USER").joinedAt(LocalDateTime.of(2026, 1, 2, 0, 0, 0)).build();

        when(taskListService.getMembers(1L, 1L))
                .thenReturn(List.of(member1, member2));

        mockMvc.perform(get("/api/lists/1/members"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].user_id").value(1))
                .andExpect(jsonPath("$[0].user_name").value("user"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].user_id").value(2))
                .andExpect(jsonPath("$[1].role").value("USER"));

        verify(taskListService).getMembers(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user")
    void getMembers_NotMember_ReturnsBadRequest() throws Exception {
        when(taskListService.getMembers(99L, 1L))
                .thenThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"));

        mockMvc.perform(get("/api/lists/99/members"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    @Test
    @WithMockUser(username = "user")
    void getMembers_InvalidIdFormat_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/lists/abc/members"))
                .andExpect(status().isBadRequest());
    }

    // === GET /api/lists/{id}/todos — задачи списка ===

    @Test
    @WithMockUser(username = "user")
    void getTodosByList_ValidList_ReturnsTodos() throws Exception {
        TodoDto todoDto1 = new TodoDto();
        todoDto1.setId(1L);
        todoDto1.setName("Задача 1");
        todoDto1.setDone(false);
        todoDto1.setUserId(1L);
        todoDto1.setListId(1L);
        todoDto1.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        TodoDto todoDto2 = new TodoDto();
        todoDto2.setId(2L);
        todoDto2.setName("Задача 2");
        todoDto2.setDone(true);
        todoDto2.setUserId(1L);
        todoDto2.setListId(1L);
        todoDto2.setCreatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));

        TodoResponse response1 = TodoResponse.builder()
                .id(1L).name("Задача 1").done(false).userId(1L).listId(1L).build();
        TodoResponse response2 = TodoResponse.builder()
                .id(2L).name("Задача 2").done(true).userId(1L).listId(1L).build();

        when(taskListService.getTodosByList(1L, 1L))
                .thenReturn(List.of(todoDto1, todoDto2));
        when(todoMapper.toResponse(todoDto1)).thenReturn(response1);
        when(todoMapper.toResponse(todoDto2)).thenReturn(response2);

        mockMvc.perform(get("/api/lists/1/todos"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Задача 1"))
                .andExpect(jsonPath("$[0].done").value(false))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].done").value(true));

        verify(taskListService).getTodosByList(1L, 1L);
        verify(todoMapper, times(2)).toResponse(any(TodoDto.class));
    }

    @Test
    @WithMockUser(username = "user")
    void getTodosByList_EmptyList_ReturnsEmptyArray() throws Exception {
        when(taskListService.getTodosByList(1L, 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/lists/1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "user")
    void getTodosByList_NotMember_ReturnsBadRequest() throws Exception {
        when(taskListService.getTodosByList(99L, 1L))
                .thenThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"));

        mockMvc.perform(get("/api/lists/99/todos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    // === DELETE /api/lists/{id}/leave — выход из списка ===

    @Test
    @WithMockUser(username = "user")
    void leaveList_Member_ReturnsOkWithMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L)).thenReturn("Вы покинули список");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Вы покинули список"));

        verify(taskListService).leaveList(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user")
    void leaveList_AdminWithOthers_ReturnsOkWithTransferMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L))
                .thenReturn("Вы покинули список. Права администратора переданы другому участнику");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Вы покинули список. Права администратора переданы другому участнику"));
    }

    @Test
    @WithMockUser(username = "user")
    void leaveList_AdminAlone_ReturnsOkWithDeleteMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L))
                .thenReturn("Список удалён, так как вы были единственным участником");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Список удалён, так как вы были единственным участником"));
    }

    @Test
    @WithMockUser(username = "user")
    void leaveList_NotMember_ReturnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"))
                .when(taskListService).leaveList(99L, 1L);

        mockMvc.perform(delete("/api/lists/99/leave"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    // === DELETE /api/lists/{id} — удаление списка ===

    @Test
    @WithMockUser(username = "user")
    void deleteList_Admin_ReturnsNoContent() throws Exception {
        doNothing().when(taskListService).deleteList(1L, 1L);

        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isNoContent());

        verify(taskListService).deleteList(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user")
    void deleteList_NotAdmin_ReturnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Только администратор может удалить список"))
                .when(taskListService).deleteList(1L, 1L);

        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Только администратор может удалить список"));
    }

    @Test
    @WithMockUser(username = "user")
    void deleteList_AccessDenied_ReturnsForbidden() throws Exception {
        doThrow(new AccessDeniedException("Доступ запрещён"))
                .when(taskListService).deleteList(1L, 1L);

        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteList_Unauthenticated_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user")
    void deleteList_InvalidIdFormat_ReturnsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/lists/abc"))
                .andExpect(status().isBadRequest());
    }
}
