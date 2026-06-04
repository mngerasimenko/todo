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
import ru.mngerasimenko.todolist.dto.list.InviteRequest;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;
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
        currentUser.setEmail("user@mail.ru");

        testListResponse = ListResponse.builder()
                .id(1L)
                .name("Тестовый список")
                .role("ADMIN")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0))
                .build();

        when(userService.getUserByEmail("user@mail.ru")).thenReturn(currentUser);
    }

    // === POST /api/lists — создание списка ===

    @Test
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
    void createList_SingleCharName_ReturnsCreated() throws Exception {
        // После релаксации min-длины (2 → 1) однобуквенное имя списка валидно.
        CreateListRequest request = CreateListRequest.builder()
                .name("A")
                .build();

        when(taskListService.createList("A", 1L))
                .thenReturn(testListResponse);

        mockMvc.perform(post("/api/lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(taskListService).createList("A", 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
    void getMembers_NotMember_ReturnsBadRequest() throws Exception {
        when(taskListService.getMembers(99L, 1L))
                .thenThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"));

        mockMvc.perform(get("/api/lists/99/members"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void getMembers_InvalidIdFormat_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/lists/abc/members"))
                .andExpect(status().isBadRequest());
    }

    // === GET /api/lists/{id}/todos — задачи списка ===

    @Test
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
    void getTodosByList_EmptyList_ReturnsEmptyArray() throws Exception {
        when(taskListService.getTodosByList(1L, 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/lists/1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void getTodosByList_NotMember_ReturnsBadRequest() throws Exception {
        when(taskListService.getTodosByList(99L, 1L))
                .thenThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"));

        mockMvc.perform(get("/api/lists/99/todos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    // === DELETE /api/lists/{id}/leave — выход из списка ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void leaveList_Member_ReturnsOkWithMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L)).thenReturn("Вы покинули список");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Вы покинули список"));

        verify(taskListService).leaveList(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void leaveList_AdminWithOthers_ReturnsOkWithTransferMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L))
                .thenReturn("Вы покинули список. Права администратора переданы другому участнику");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Вы покинули список. Права администратора переданы другому участнику"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void leaveList_AdminAlone_ReturnsOkWithDeleteMessage() throws Exception {
        when(taskListService.leaveList(1L, 1L))
                .thenReturn("Список удалён, так как вы были единственным участником");

        mockMvc.perform(delete("/api/lists/1/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Список удалён, так как вы были единственным участником"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void leaveList_NotMember_ReturnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Вы не являетесь участником данного списка"))
                .when(taskListService).leaveList(99L, 1L);

        mockMvc.perform(delete("/api/lists/99/leave"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вы не являетесь участником данного списка"));
    }

    // === DELETE /api/lists/{id} — удаление списка ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void deleteList_Admin_ReturnsNoContent() throws Exception {
        doNothing().when(taskListService).deleteList(1L, 1L);

        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isNoContent());

        verify(taskListService).deleteList(1L, 1L);
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void deleteList_NotAdmin_ReturnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Только администратор может удалить список"))
                .when(taskListService).deleteList(1L, 1L);

        mockMvc.perform(delete("/api/lists/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Только администратор может удалить список"));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
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
    @WithMockUser(username = "user@mail.ru")
    void deleteList_InvalidIdFormat_ReturnsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/lists/abc"))
                .andExpect(status().isBadRequest());
    }

    // === PATCH /api/lists/{id} — переименование списка ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void updateList_ValidRequest_ReturnsOkWithUpdatedName() throws Exception {
        ListResponse updated = ListResponse.builder()
                .id(1L)
                .name("Покупки")
                .role("ADMIN")
                .build();

        when(taskListService.updateList(eq(1L), eq(1L), eq("Покупки")))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/lists/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Покупки\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Покупки"));

        verify(taskListService).updateList(1L, 1L, "Покупки");
    }

    // === PATCH /api/lists/{id}/personalization — per-user цвет ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void updatePersonalization_ValidColor_ReturnsOk() throws Exception {
        ListResponse updated = ListResponse.builder()
                .id(1L).name("Покупки").color("#22C55E").role("ADMIN").build();

        when(taskListService.updatePersonalization(eq(1L), eq(1L), eq("#22C55E")))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/lists/1/personalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"#22C55E\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#22C55E"));

        verify(taskListService).updatePersonalization(1L, 1L, "#22C55E");
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void updatePersonalization_InvalidColor_ReturnsBadRequest() throws Exception {
        // Не соответствует ^#[0-9a-fA-F]{6}$ — отлетает на @Valid до сервиса
        mockMvc.perform(patch("/api/lists/1/personalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"red\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user@todolist.ru")
    void patchList_nameWithAngleBrackets_returns400() throws Exception {
        // XSS-guard: имя со скобками < > должно отлететь на @Pattern (^[^<>]*$)
        mockMvc.perform(patch("/api/lists/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"<script>alert(1)</script>\"}"))
                .andExpect(status().isBadRequest());
    }

    // === PATCH /api/lists/reorder — bulk-обновление позиций (per-user) ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderLists_validInput_returns200() throws Exception {
        mockMvc.perform(patch("/api/lists/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":10,\"position\":1},{\"id\":11,\"position\":0}]}"))
                .andExpect(status().isOk());

        verify(taskListService).reorderLists(eq(1L), argThat(items -> items.size() == 2));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderLists_emptyItems_returns400() throws Exception {
        // @NotEmpty на items — пустой массив отлетает на @Valid до сервиса
        mockMvc.perform(patch("/api/lists/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderLists_duplicateIds_returns400() throws Exception {
        // Контрактная проверка: IllegalArgumentException из сервиса должен мапиться
        // в HTTP 400 через GlobalExceptionHandler (а не падать в 500).
        doThrow(new IllegalArgumentException("Duplicate list ids in reorder request"))
                .when(taskListService).reorderLists(eq(1L), any());

        mockMvc.perform(patch("/api/lists/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":10,\"position\":0},{\"id\":10,\"position\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderLists_duplicatePositions_returns400() throws Exception {
        // Locks API contract: duplicate positions → HTTP 400 via GlobalExceptionHandler.
        doThrow(new IllegalArgumentException("Duplicate positions in reorder request"))
                .when(taskListService).reorderLists(eq(1L), any());

        mockMvc.perform(patch("/api/lists/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":10,\"position\":0},{\"id\":11,\"position\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    // === PATCH /api/lists/{id}/todos/reorder — bulk-обновление позиций задач (per-список) ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderTodos_validInput_returns200() throws Exception {
        mockMvc.perform(patch("/api/lists/42/todos/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":100,\"position\":1},{\"id\":101,\"position\":0}]}"))
                .andExpect(status().isOk());

        verify(taskListService).reorderTodos(eq(42L), eq(1L), argThat(items -> items.size() == 2));
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderTodos_emptyItems_returns400() throws Exception {
        // @NotEmpty на items — пустой массив отлетает на @Valid до сервиса
        mockMvc.perform(patch("/api/lists/42/todos/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskListService);
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderTodos_duplicateIds_returns400() throws Exception {
        // Контрактная проверка: IllegalArgumentException из сервиса должен мапиться в HTTP 400
        doThrow(new IllegalArgumentException("Duplicate todo ids in reorder request"))
                .when(taskListService).reorderTodos(eq(42L), eq(1L), any());

        mockMvc.perform(patch("/api/lists/42/todos/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":100,\"position\":0},{\"id\":100,\"position\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@mail.ru")
    void reorderTodos_duplicatePositions_returns400() throws Exception {
        // Locks API contract: duplicate positions → HTTP 400 via GlobalExceptionHandler.
        doThrow(new IllegalArgumentException("Duplicate positions in reorder request"))
                .when(taskListService).reorderTodos(eq(42L), eq(1L), any());

        mockMvc.perform(patch("/api/lists/42/todos/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":100,\"position\":0},{\"id\":101,\"position\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    // === POST /api/lists/{id}/invite — приглашение по email ===

    @Test
    @WithMockUser(username = "user@mail.ru")
    void createInvite_TooLongEmail_ReturnsBadRequest() throws Exception {
        // Arrange — email длиной MAX_LENGTH+1 (точно превышает @Size).
        // Local-part 64 (макс по RFC 5321), чтобы пройти @Email и сработал именно @Size.
        String overlongEmail = "a".repeat(64) + "@" + "b".repeat(EmailValidation.MAX_LENGTH - 67) + ".io";
        String expectedMessage = "Email must not exceed " + EmailValidation.MAX_LENGTH + " characters";
        InviteRequest request = InviteRequest.builder()
                .email(overlongEmail)
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/lists/1/invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value(expectedMessage));

        verifyNoInteractions(taskListService);
    }
}
