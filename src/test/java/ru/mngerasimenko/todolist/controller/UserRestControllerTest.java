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
import ru.mngerasimenko.todolist.dto.ChangeNameRequest;
import ru.mngerasimenko.todolist.dto.SortPreferencesRequest;
import ru.mngerasimenko.todolist.dto.SubscriptionStatusResponse;
import ru.mngerasimenko.todolist.dto.UpdateColorsRequest;
import ru.mngerasimenko.todolist.dto.UpdateEmailLocaleRequest;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.SubscriptionService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserRestController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class UserRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private SubscriptionService subscriptionService;

    @MockitoBean
    private PushNotificationService pushNotificationService;

    private UserDto testUserDto;
    private UserResponse testUserResponse;
    private UserRequest testUserRequest;


    @BeforeEach
    void setUp() {
        testUserDto = new UserDto();
        testUserDto.setId(1L);
        testUserDto.setName("testuser");
        testUserDto.setEmail("test@mail.ru");
        testUserDto.setPassword("password123");

        testUserResponse = new UserResponse();
        testUserResponse.setId(1L);
        testUserResponse.setName("testuser");
        testUserResponse.setEmail("test@mail.ru");

        testUserRequest = new UserRequest();
        testUserRequest.setName("newuser");
        testUserRequest.setEmail("new@mail.ru");
        testUserRequest.setPassword("newpass");
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void getSubscriptionStatus_ReturnsSubscriptionInfo() throws Exception {
        SubscriptionStatusResponse subscriptionResponse = SubscriptionStatusResponse.builder()
                .subscriptionType("FREE")
                .betaTester(false)
                .limits(SubscriptionStatusResponse.Limits.builder()
                        .maxLists(2)
                        .maxTasksPerList(30)
                        .maxMembersPerList(3)
                        .privateTasksAllowed(false)
                        .build())
                .usage(SubscriptionStatusResponse.Usage.builder()
                        .listsCount(1)
                        .canCreateList(true)
                        .build())
                .build();

        when(subscriptionService.getSubscriptionStatus("test@mail.ru")).thenReturn(subscriptionResponse);

        mockMvc.perform(get("/api/users/me/subscription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_type").value("FREE"))
                .andExpect(jsonPath("$.is_beta_tester").value(false))
                .andExpect(jsonPath("$.limits.max_lists").value(2))
                .andExpect(jsonPath("$.limits.max_tasks_per_list").value(30))
                .andExpect(jsonPath("$.limits.max_members_per_list").value(3))
                .andExpect(jsonPath("$.limits.private_tasks_allowed").value(false))
                .andExpect(jsonPath("$.usage.lists_count").value(1))
                .andExpect(jsonPath("$.usage.can_create_list").value(true));

        verify(subscriptionService).getSubscriptionStatus("test@mail.ru");
    }

    @Test
    void getSubscriptionStatus_WithoutAuth_Returns401() throws Exception {
        mockMvc.perform(get("/api/users/me/subscription"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createUser_ValidRequest_ReturnsCreatedUser() throws Exception {
        UserDto createdUserDto = new UserDto();
        createdUserDto.setId(2L);
        createdUserDto.setName("newuser");
        createdUserDto.setEmail("new@mail.ru");
        createdUserDto.setPassword("newpass");

        UserResponse createdResponse = new UserResponse();
        createdResponse.setId(2L);
        createdResponse.setName("newuser");
        createdResponse.setEmail("new@mail.ru");

        when(userMapper.toDto(any(UserRequest.class))).thenReturn(createdUserDto);
        when(userService.createUser(any(UserDto.class))).thenReturn(createdUserDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(createdResponse);

        mockMvc.perform(post("/api/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.name").value("newuser"))
                .andExpect(jsonPath("$.email").value("new@mail.ru"));

        verify(userMapper, times(1)).toDto(any(UserRequest.class));
        verify(userService, times(1)).createUser(any(UserDto.class));
        verify(userMapper, times(1)).toResponse(any(UserDto.class));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createUser_InvalidRequest_ReturnsBadRequest() throws Exception {
        UserRequest invalidRequest = new UserRequest();

        mockMvc.perform(post("/api/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createUser_DuplicateEmail_ReturnsConflict() throws Exception {
        when(userMapper.toDto(any(UserRequest.class))).thenReturn(testUserDto);
        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new IllegalArgumentException("User with email test@mail.ru already exists"));

        mockMvc.perform(post("/api/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("User with email test@mail.ru already exists"));
    }


    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void showAll_ReturnsListOfUsers() throws Exception {
        UserDto user1 = new UserDto();
        user1.setId(1L);
        user1.setName("user1");
        user1.setEmail("user1@mail.ru");

        UserDto user2 = new UserDto();
        user2.setId(2L);
        user2.setName("user2");
        user2.setEmail("user2@mail.ru");

        UserResponse response1 = new UserResponse();
        response1.setId(1L);
        response1.setName("user1");
        response1.setEmail("user1@mail.ru");

        UserResponse response2 = new UserResponse();
        response2.setId(2L);
        response2.setName("user2");
        response2.setEmail("user2@mail.ru");

        when(userService.getAll()).thenReturn(Arrays.asList(user1, user2));
        when(userMapper.toResponse(user1)).thenReturn(response1);
        when(userMapper.toResponse(user2)).thenReturn(response2);

        mockMvc.perform(get("/api/users/all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("user1"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("user2"));

        verify(userService, times(1)).getAll();
        verify(userMapper, times(2)).toResponse(any(UserDto.class));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void showAll_EmptyList_ReturnsEmptyArray() throws Exception {
        when(userService.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/users/all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(userService, times(1)).getAll();
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void getUserById_OwnAccount_ReturnsUser() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        when(userService.getUserById(1L)).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        mockMvc.perform(get("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("testuser"))
                .andExpect(jsonPath("$.email").value("test@mail.ru"));

        verify(userService, times(1)).getUserById(1L);
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void getUserById_OtherAccount_ReturnsForbidden() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        mockMvc.perform(get("/api/users/999")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // === Тесты updateUser с проверкой прав доступа ===

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateUser_OwnAccount_ReturnsUpdatedUser() throws Exception {
        // testuser (id=1) обновляет свой аккаунт
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        UserDto updatedUserDto = new UserDto();
        updatedUserDto.setId(1L);
        updatedUserDto.setName("updateduser");
        updatedUserDto.setEmail("updated@mail.ru");
        updatedUserDto.setPassword("newpass");

        UserResponse updatedResponse = new UserResponse();
        updatedResponse.setId(1L);
        updatedResponse.setName("updateduser");
        updatedResponse.setEmail("updated@mail.ru");

        when(userMapper.toDto(any(UserRequest.class))).thenReturn(updatedUserDto);
        when(userService.updateUser(eq(1L), any(UserDto.class))).thenReturn(updatedUserDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(updatedResponse);

        mockMvc.perform(put("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("updateduser"))
                .andExpect(jsonPath("$.email").value("updated@mail.ru"));

        verify(userService, times(1)).updateUser(eq(1L), any(UserDto.class));
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateUser_OtherAccount_ReturnsForbidden() throws Exception {
        // testuser (id=1) пытается обновить чужой аккаунт (id=2)
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        mockMvc.perform(put("/api/users/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Доступ запрещён: можно изменять только свой аккаунт"));

        verify(userService, never()).updateUser(anyLong(), any(UserDto.class));
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateUser_WithNonExistentId_ReturnsNotFound() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        when(userMapper.toDto(any(UserRequest.class))).thenReturn(testUserDto);
        when(userService.updateUser(eq(1L), any(UserDto.class)))
                .thenThrow(new UserNotFoundException("User not found with id: 1"));

        mockMvc.perform(put("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found with id: 1"));
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateUser_WithDuplicateEmail_ReturnsBadRequest() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        when(userMapper.toDto(any(UserRequest.class))).thenReturn(testUserDto);
        when(userService.updateUser(eq(1L), any(UserDto.class)))
                .thenThrow(new IllegalArgumentException("Email test@mail.ru is already taken"));

        mockMvc.perform(put("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email test@mail.ru is already taken"));
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateUser_MissingRequiredFields_ReturnsBadRequest() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        UserRequest invalidRequest = new UserRequest();

        mockMvc.perform(put("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    // === Тесты deleteUser с проверкой прав доступа ===

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void deleteUser_OwnAccount_ReturnsSuccessMessage() throws Exception {
        // testuser (id=1) удаляет свой аккаунт
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        mockMvc.perform(delete("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService, times(1)).delete(1L);
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void deleteUser_OtherAccount_ReturnsForbidden() throws Exception {
        // testuser (id=1) пытается удалить чужой аккаунт (id=2)
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        mockMvc.perform(delete("/api/users/2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Доступ запрещён: можно изменять только свой аккаунт"));

        verify(userService, never()).delete(anyLong());
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void deleteUser_WithNonExistentId_ReturnsNotFound() throws Exception {
        // testuser (id=1) удаляет свой аккаунт, но пользователь не найден в БД
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        doThrow(new UserNotFoundException("User not found with id: 1"))
                .when(userService).delete(1L);

        mockMvc.perform(delete("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found with id: 1"));

        verify(userService, times(1)).delete(1L);
    }

    // === Тесты updateColors с проверкой прав доступа ===

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateColors_OwnAccount_ReturnsUpdatedUser() throws Exception {
        // testuser (id=1) обновляет свои цвета
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        UpdateColorsRequest colorsRequest = new UpdateColorsRequest("#FF0000", "#00FF00");

        UserDto updatedDto = new UserDto();
        updatedDto.setId(1L);
        updatedDto.setName("testuser");
        updatedDto.setEmail("test@mail.ru");
        updatedDto.setCreatedTaskColor("#FF0000");
        updatedDto.setCompletedTaskColor("#00FF00");

        UserResponse updatedResponse = new UserResponse();
        updatedResponse.setId(1L);
        updatedResponse.setName("testuser");
        updatedResponse.setEmail("test@mail.ru");
        updatedResponse.setCreatedTaskColor("#FF0000");
        updatedResponse.setCompletedTaskColor("#00FF00");

        when(userService.updateColors(eq(1L), eq("#FF0000"), eq("#00FF00"))).thenReturn(updatedDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(updatedResponse);

        mockMvc.perform(put("/api/users/1/colors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(colorsRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.created_task_color").value("#FF0000"))
                .andExpect(jsonPath("$.completed_task_color").value("#00FF00"));

        verify(userService, times(1)).updateColors(eq(1L), eq("#FF0000"), eq("#00FF00"));
    }

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void updateColors_OtherAccount_ReturnsForbidden() throws Exception {
        // testuser (id=1) пытается обновить цвета чужого аккаунта (id=2)
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        UpdateColorsRequest colorsRequest = new UpdateColorsRequest("#FF0000", "#00FF00");

        mockMvc.perform(put("/api/users/2/colors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(colorsRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Доступ запрещён: можно изменять только свой аккаунт"));

        verify(userService, never()).updateColors(anyLong(), anyString(), anyString());
    }

    // === Тест getCurrentUser ===

    @Test
    @WithMockUser(username = "test@mail.ru", roles = {"USER"})
    void getCurrentUser_ReturnsCurrentUser() throws Exception {
        when(userService.getUserDtoForResponse("test@mail.ru")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("testuser"))
                .andExpect(jsonPath("$.email").value("test@mail.ru"));

        verify(userService, times(1)).getUserDtoForResponse("test@mail.ru");
        verify(userMapper, times(1)).toResponse(testUserDto);
    }

    // === PATCH /me/email-locale ===

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateEmailLocale_ValidLocale_Returns204() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        mockMvc.perform(patch("/api/users/me/email-locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"en\"}"))
                .andExpect(status().isNoContent());

        verify(userService).updateEmailLocale(1L, "en");
    }

    @Test
    void updateEmailLocale_NoAuth_Returns401() throws Exception {
        mockMvc.perform(patch("/api/users/me/email-locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"en\"}"))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).updateEmailLocale(any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateEmailLocale_BlankLocale_Returns400() throws Exception {
        mockMvc.perform(patch("/api/users/me/email-locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateEmailLocale(any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateEmailLocale_TooLongLocale_Returns400() throws Exception {
        // 9 символов > @Size(max=8)
        mockMvc.perform(patch("/api/users/me/email-locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"123456789\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateEmailLocale(any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateEmailLocale_NonBcp47Pattern_Returns400() throws Exception {
        // Невалидный BCP-47: цифровой и spec-символ-содержащий. До @Pattern попадали в БД.
        for (String bad : new String[]{"123", "*", "!@#$", "ru-!", "  X"}) {
            mockMvc.perform(patch("/api/users/me/email-locale")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"locale\": \"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(userService, never()).updateEmailLocale(any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateEmailLocale_ValidBcp47Variants_AllPass() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        for (String ok : new String[]{"ru", "en", "pt-BR", "zh-Hant"}) {
            mockMvc.perform(patch("/api/users/me/email-locale")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"locale\": \"" + ok + "\"}"))
                    .andExpect(status().isNoContent());
            verify(userService).updateEmailLocale(1L, ok);
        }
    }

    // === Тест create + get ===

    @Test
    @WithMockUser(username = "created@mail.ru", roles = {"USER"})
    void createUserThenGetUser_ReturnsCreatedUser() throws Exception {
        UserDto createdUserDto = new UserDto();
        createdUserDto.setId(3L);
        createdUserDto.setName("createduser");
        createdUserDto.setEmail("created@mail.ru");

        UserResponse createdResponse = new UserResponse();
        createdResponse.setId(3L);
        createdResponse.setName("createduser");
        createdResponse.setEmail("created@mail.ru");

        when(userMapper.toDto(any(UserRequest.class))).thenReturn(createdUserDto);
        when(userService.createUser(any(UserDto.class))).thenReturn(createdUserDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(createdResponse);

        mockMvc.perform(post("/api/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testUserRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3));

        when(userService.getUserByEmail("created@mail.ru")).thenReturn(createdUserDto);
        when(userService.getUserById(3L)).thenReturn(createdUserDto);

        mockMvc.perform(get("/api/users/3")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("createduser"));
    }

    // === PATCH /me/sort-preferences (Task 5) ===

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateSortPreferences_ValidRequest_Returns200() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);

        UserDto updatedDto = new UserDto();
        updatedDto.setId(1L);
        updatedDto.setListsSortMode("ALPHABETICAL");
        updatedDto.setListsSortDirection("ASC");
        updatedDto.setTodosSortMode("CREATED_AT");
        updatedDto.setTodosSortDirection("DESC");

        UserResponse response = UserResponse.builder()
                .id(1L)
                .listsSortMode("ALPHABETICAL")
                .listsSortDirection("ASC")
                .todosSortMode("CREATED_AT")
                .todosSortDirection("DESC")
                .build();

        when(userService.updateSortPreferences(eq(1L), eq("test@mail.ru"),
                any(SortPreferencesRequest.class))).thenReturn(updatedDto);
        when(userMapper.toResponse(updatedDto)).thenReturn(response);

        mockMvc.perform(patch("/api/users/me/sort-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listsSortMode\":\"ALPHABETICAL\",\"listsSortDirection\":\"ASC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lists_sort_mode").value("ALPHABETICAL"))
                .andExpect(jsonPath("$.lists_sort_direction").value("ASC"));

        verify(userService).updateSortPreferences(eq(1L), eq("test@mail.ru"),
                any(SortPreferencesRequest.class));
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateSortPreferences_InvalidSortMode_Returns400() throws Exception {
        mockMvc.perform(patch("/api/users/me/sort-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listsSortMode\":\"INVALID_MODE\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateSortPreferences(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateSortPreferences_InvalidDirection_Returns400() throws Exception {
        mockMvc.perform(patch("/api/users/me/sort-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listsSortDirection\":\"UP\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateSortPreferences(any(), any(), any());
    }

    @Test
    void updateSortPreferences_NoAuth_Returns401() throws Exception {
        mockMvc.perform(patch("/api/users/me/sort-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listsSortMode\":\"ALPHABETICAL\"}"))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).updateSortPreferences(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateName_Authenticated_ReturnsOk() throws Exception {
        when(userService.getUserByEmail("test@mail.ru")).thenReturn(testUserDto);
        UserDto updated = new UserDto();
        updated.setId(1L);
        updated.setName("Новое Имя");
        updated.setEmail("test@mail.ru");
        when(userService.updateName(1L, "Новое Имя")).thenReturn(updated);
        UserResponse resp = new UserResponse();
        resp.setId(1L);
        resp.setName("Новое Имя");
        resp.setEmail("test@mail.ru");
        when(userMapper.toResponse(updated)).thenReturn(resp);

        ChangeNameRequest request = ChangeNameRequest.builder().name("Новое Имя").build();

        mockMvc.perform(patch("/api/users/me/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Новое Имя"));

        verify(userService).updateName(1L, "Новое Имя");
    }

    @Test
    void updateName_NoAuth_ReturnsUnauthorized() throws Exception {
        ChangeNameRequest request = ChangeNameRequest.builder().name("Имя Имя").build();
        mockMvc.perform(patch("/api/users/me/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "test@mail.ru")
    void updateName_BlankName_ReturnsBadRequest() throws Exception {
        ChangeNameRequest request = ChangeNameRequest.builder().name("").build();
        mockMvc.perform(patch("/api/users/me/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
