package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.InviteInfoResponse;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TaskListMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;
import ru.mngerasimenko.todolist.model.InviteToken;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.util.TokenUtils;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskListServiceImplTest {

    @Mock
    private TaskListRepository taskListRepository;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TaskListMapper taskListMapper;

    @Mock
    private TodoMapper todoMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailProperties emailProperties;

    @InjectMocks
    private TaskListServiceImpl taskListService;

    private User testUser;
    private TaskList testTaskList;
    private TaskListUser testTaskListUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");
        testUser.setPassword("$2a$10$hash");

        testTaskList = new TaskList("TestList", "$2a$10$hashedListPass");
        testTaskList.setId(10L);

        testTaskListUser = new TaskListUser();
        testTaskListUser.setId(new TaskListUserId(10L, 1L));
        testTaskListUser.setTaskList(testTaskList);
        testTaskListUser.setUser(testUser);
        testTaskListUser.setRole(TaskListRole.ADMIN);
    }

    // --- createList ---

    @Test
    void createList_WithValidData_ReturnsListResponse() {
        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("ADMIN").build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("pass123")).thenReturn("$2a$10$encodedPass");
        when(taskListRepository.saveAndFlush(any(TaskList.class))).thenReturn(testTaskList);
        when(taskListUserRepository.save(any(TaskListUser.class))).thenReturn(testTaskListUser);
        when(taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN)).thenReturn(expectedResponse);

        ListResponse result = taskListService.createList("TestList", "pass123", 1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(taskListRepository).saveAndFlush(any(TaskList.class));
        verify(taskListUserRepository).save(any(TaskListUser.class));
    }

    @Test
    void createList_WithDuplicateName_ThrowsIllegalArgumentException() {
        // Уникальность гарантирует БД — saveAndFlush бросает DataIntegrityViolationException
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("pass")).thenReturn("$2a$10$encodedPass");
        when(taskListRepository.saveAndFlush(any(TaskList.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: name"));

        assertThatThrownBy(() -> taskListService.createList("TestList", "pass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestList");
    }

    @Test
    void createList_WithNonExistentUser_ThrowsUserNotFoundException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.createList("NewList", "pass", 999L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(taskListRepository, never()).saveAndFlush(any());
    }

    // --- joinList ---

    @Test
    void joinList_WithValidCredentials_ReturnsListResponse() {
        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("USER").build();

        when(taskListRepository.findByName("TestList")).thenReturn(Optional.of(testTaskList));
        when(passwordEncoder.matches("pass123", "$2a$10$hashedListPass")).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(testUser));
        // findBy возвращает пустой Optional — пользователь не в списке
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 2L)).thenReturn(Optional.empty());
        // В списке уже есть ADMIN — вступающий получает роль USER
        when(taskListUserRepository.existsByIdListIdAndRole(10L, TaskListRole.ADMIN)).thenReturn(true);
        when(taskListUserRepository.save(any(TaskListUser.class))).thenReturn(testTaskListUser);
        when(taskListMapper.toResponse(testTaskList, TaskListRole.USER)).thenReturn(expectedResponse);

        ListResponse result = taskListService.joinList("TestList", "pass123", 2L);

        assertThat(result).isNotNull();
        assertThat(result.getRole()).isEqualTo("USER");
        verify(taskListUserRepository).save(any(TaskListUser.class));
    }

    @Test
    void joinList_WhenNoAdmin_AssignsAdminRole() {
        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("ADMIN").build();

        when(taskListRepository.findByName("TestList")).thenReturn(Optional.of(testTaskList));
        when(passwordEncoder.matches("pass123", "$2a$10$hashedListPass")).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(testUser));
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 2L)).thenReturn(Optional.empty());
        // В списке нет ADMIN — вступающий автоматически получает роль ADMIN
        when(taskListUserRepository.existsByIdListIdAndRole(10L, TaskListRole.ADMIN)).thenReturn(false);
        when(taskListUserRepository.save(any(TaskListUser.class))).thenReturn(testTaskListUser);
        when(taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN)).thenReturn(expectedResponse);

        ListResponse result = taskListService.joinList("TestList", "pass123", 2L);

        assertThat(result).isNotNull();
        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(taskListUserRepository).save(any(TaskListUser.class));
    }

    @Test
    void joinList_WhenAlreadyMember_ReturnsExistingRole() {
        TaskListUser existingMembership = new TaskListUser();
        existingMembership.setRole(TaskListRole.ADMIN);

        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("ADMIN").build();

        when(taskListRepository.findByName("TestList")).thenReturn(Optional.of(testTaskList));
        when(passwordEncoder.matches("pass123", "$2a$10$hashedListPass")).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        // findBy возвращает существующую запись — один запрос вместо existsBy + findBy
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(existingMembership));
        when(taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN)).thenReturn(expectedResponse);

        ListResponse result = taskListService.joinList("TestList", "pass123", 1L);

        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(taskListUserRepository, never()).save(any());
    }

    @Test
    void joinList_WithWrongPassword_ThrowsIllegalArgumentException() {
        when(taskListRepository.findByName("TestList")).thenReturn(Optional.of(testTaskList));
        when(passwordEncoder.matches("wrongpass", "$2a$10$hashedListPass")).thenReturn(false);

        assertThatThrownBy(() -> taskListService.joinList("TestList", "wrongpass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пароль");
    }

    @Test
    void joinList_WithNonExistentList_ThrowsIllegalArgumentException() {
        when(taskListRepository.findByName("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.joinList("Unknown", "pass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    // --- getListsByUserId ---

    @Test
    void getListsByUserId_ReturnsListOfLists() {
        ListResponse response = ListResponse.builder()
                .id(10L).name("TestList").role("ADMIN").build();

        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of(testTaskListUser));
        when(taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN)).thenReturn(response);

        List<ListResponse> result = taskListService.getListsByUserId(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("TestList");
    }

    @Test
    void getListsByUserId_WithNoLists_ReturnsEmptyList() {
        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of());

        List<ListResponse> result = taskListService.getListsByUserId(1L);

        assertThat(result).isEmpty();
    }

    // --- getMembers ---

    @Test
    void getMembers_WhenUserIsMember_ReturnsMemberList() {
        ListMemberResponse memberResponse = ListMemberResponse.builder()
                .userId(1L).userName("testuser").role("ADMIN").build();

        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 1L)).thenReturn(true);
        when(taskListUserRepository.findByIdListId(10L)).thenReturn(List.of(testTaskListUser));
        when(taskListMapper.toMemberResponse(testTaskListUser)).thenReturn(memberResponse);

        List<ListMemberResponse> result = taskListService.getMembers(10L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserName()).isEqualTo("testuser");
    }

    @Test
    void getMembers_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> taskListService.getMembers(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");
    }

    // --- getTodosByList ---

    @Test
    void getTodosByList_WhenUserIsMember_ReturnsTodos() {
        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Task");

        TodoDto todoDto = new TodoDto();
        todoDto.setId(1L);
        todoDto.setName("Task");

        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 1L)).thenReturn(true);
        when(todoRepository.findByListIdVisibleToUser(10L, 1L)).thenReturn(List.of(todo));
        when(todoMapper.toDto(todo)).thenReturn(todoDto);

        List<TodoDto> result = taskListService.getTodosByList(10L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Task");
    }

    @Test
    void getTodosByList_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> taskListService.getTodosByList(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");
    }

    // --- leaveList ---

    @Test
    void leaveList_WhenUserIsMember_DeletesPrivateTodosAndRemovesMembership() {
        TaskListUser memberUser = new TaskListUser();
        memberUser.setId(new TaskListUserId(10L, 1L));
        memberUser.setRole(TaskListRole.USER);

        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(memberUser));

        String message = taskListService.leaveList(10L, 1L);

        assertThat(message).contains("покинули список");
        verify(todoRepository).deletePrivateTodosByListIdAndUserId(10L, 1L);
        verify(taskListUserRepository).deleteByListIdAndUserId(10L, 1L);
    }

    @Test
    void leaveList_WhenAdminAlone_DeletesEntireList() {
        TaskListUser adminUser = new TaskListUser();
        adminUser.setId(new TaskListUserId(10L, 1L));
        adminUser.setRole(TaskListRole.ADMIN);

        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(adminUser));
        when(taskListUserRepository.findByIdListId(10L))
                .thenReturn(List.of(adminUser));

        String message = taskListService.leaveList(10L, 1L);

        assertThat(message).contains("удалён");
        verify(todoRepository).deleteByListId(10L);
        verify(taskListUserRepository).deleteByListId(10L);
        verify(taskListRepository).deleteByListId(10L);
    }

    @Test
    void leaveList_WhenAdminWithOthers_TransfersAdminAndLeaves() {
        User user1 = new User(); user1.setId(1L);
        User user2 = new User(); user2.setId(2L);

        TaskListUser adminUser = new TaskListUser();
        adminUser.setId(new TaskListUserId(10L, 1L));
        adminUser.setRole(TaskListRole.ADMIN);
        adminUser.setUser(user1);

        TaskListUser otherUser = new TaskListUser();
        otherUser.setId(new TaskListUserId(10L, 2L));
        otherUser.setRole(TaskListRole.USER);
        otherUser.setUser(user2);

        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(adminUser));
        when(taskListUserRepository.findByIdListId(10L))
                .thenReturn(List.of(adminUser, otherUser));

        String message = taskListService.leaveList(10L, 1L);

        assertThat(message).contains("переданы");
        assertThat(otherUser.getRole()).isEqualTo(TaskListRole.ADMIN);
        verify(taskListUserRepository).saveAndFlush(otherUser);
        verify(todoRepository).deletePrivateTodosByListIdAndUserId(10L, 1L);
        verify(taskListUserRepository).deleteByListIdAndUserId(10L, 1L);
    }

    @Test
    void leaveList_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.leaveList(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");

        verify(taskListUserRepository, never()).deleteByListIdAndUserId(anyLong(), anyLong());
    }

    // --- deleteList ---

    @Test
    void deleteList_WhenUserIsAdmin_DeletesAllTodosAndMembersAndList() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(testTaskListUser));

        taskListService.deleteList(10L, 1L);

        verify(inviteTokenRepository).deleteByListId(10L);
        verify(todoRepository).deleteByListId(10L);
        verify(taskListUserRepository).deleteByListId(10L);
        verify(taskListRepository).deleteByListId(10L);
    }

    @Test
    void deleteList_WhenUserIsNotAdmin_ThrowsIllegalArgumentException() {
        TaskListUser memberUser = new TaskListUser();
        memberUser.setId(new TaskListUserId(10L, 2L));
        memberUser.setRole(TaskListRole.USER);

        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 2L))
                .thenReturn(Optional.of(memberUser));

        assertThatThrownBy(() -> taskListService.deleteList(10L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("администратор");

        verify(todoRepository, never()).deleteByListId(anyLong());
        verify(taskListUserRepository, never()).deleteByListId(anyLong());
        verify(taskListRepository, never()).deleteByListId(anyLong());
    }

    @Test
    void deleteList_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.deleteList(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");

        verify(todoRepository, never()).deleteByListId(anyLong());
        verify(taskListRepository, never()).deleteByListId(anyLong());
    }

    // --- createInvite ---

    @Test
    void createInvite_WhenAdmin_ReturnsInviteResponse() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(testTaskListUser));
        when(emailProperties.getBaseUrl()).thenReturn("https://todo.keepware.ru");
        when(emailProperties.getInviteTokenTtlHours()).thenReturn(24);
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(i -> i.getArgument(0));

        InviteResponse result = taskListService.createInvite(10L, 1L, null);

        assertThat(result).isNotNull();
        assertThat(result.getInviteLink()).startsWith("https://todo.keepware.ru/invite/");
        assertThat(result.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        verify(inviteTokenRepository).save(any(InviteToken.class));
        verify(emailService, never()).sendInviteEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createInvite_WhenAdminWithEmail_SendsInviteEmail() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(testTaskListUser));
        when(emailProperties.getBaseUrl()).thenReturn("https://todo.keepware.ru");
        when(emailProperties.getInviteTokenTtlHours()).thenReturn(24);
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(i -> i.getArgument(0));

        InviteResponse result = taskListService.createInvite(10L, 1L, "friend@mail.ru");

        assertThat(result).isNotNull();
        verify(emailService).sendInviteEmail(eq("friend@mail.ru"), anyString(), eq("TestList"), eq("testuser"));
    }

    @Test
    void createInvite_WhenNotAdmin_ThrowsIllegalArgumentException() {
        TaskListUser memberUser = new TaskListUser();
        memberUser.setId(new TaskListUserId(10L, 2L));
        memberUser.setRole(TaskListRole.USER);

        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 2L))
                .thenReturn(Optional.of(memberUser));

        assertThatThrownBy(() -> taskListService.createInvite(10L, 2L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("администратор");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    void createInvite_WhenNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.createInvite(10L, 99L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");
    }

    // --- getInviteInfo ---

    @Test
    void getInviteInfo_WithValidToken_ReturnsInviteInfo() {
        String rawToken = "test-token-uuid";
        String tokenHash = TokenUtils.sha256(rawToken);
        InviteToken inviteToken = new InviteToken(tokenHash, testTaskList, testUser,
                LocalDateTime.now().plusHours(12));

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(inviteToken));

        InviteInfoResponse result = taskListService.getInviteInfo(rawToken);

        assertThat(result.getListName()).isEqualTo("TestList");
        assertThat(result.getInviterName()).isEqualTo("testuser");
        assertThat(result.getExpiresAt()).isNotNull();
    }

    @Test
    void getInviteInfo_WithInvalidToken_ThrowsTokenExpiredException() {
        String rawToken = "invalid-token";
        String tokenHash = TokenUtils.sha256(rawToken);

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.getInviteInfo(rawToken))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("не найдено");
    }

    @Test
    void getInviteInfo_WithExpiredToken_ThrowsTokenExpiredException() {
        String rawToken = "expired-token";
        String tokenHash = TokenUtils.sha256(rawToken);
        InviteToken inviteToken = new InviteToken(tokenHash, testTaskList, testUser,
                LocalDateTime.now().minusHours(1));

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(inviteToken));

        assertThatThrownBy(() -> taskListService.getInviteInfo(rawToken))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("истёк");
    }

    // --- acceptInvite ---

    @Test
    void acceptInvite_WithValidToken_JoinsListAndReturnsResponse() {
        String rawToken = "accept-token";
        String tokenHash = TokenUtils.sha256(rawToken);
        InviteToken inviteToken = new InviteToken(tokenHash, testTaskList, testUser,
                LocalDateTime.now().plusHours(12));

        User newUser = new User();
        newUser.setId(2L);
        newUser.setName("newuser");

        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("USER").build();

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(inviteToken));
        when(userRepository.findById(2L)).thenReturn(Optional.of(newUser));
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 2L)).thenReturn(Optional.empty());
        when(taskListUserRepository.existsByIdListIdAndRole(10L, TaskListRole.ADMIN)).thenReturn(true);
        when(taskListUserRepository.save(any(TaskListUser.class))).thenAnswer(i -> i.getArgument(0));
        when(taskListMapper.toResponse(testTaskList, TaskListRole.USER)).thenReturn(expectedResponse);

        ListResponse result = taskListService.acceptInvite(rawToken, 2L);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getRole()).isEqualTo("USER");
        verify(taskListUserRepository).save(any(TaskListUser.class));
    }

    @Test
    void acceptInvite_WhenAlreadyMember_ReturnsExistingRole() {
        String rawToken = "already-member-token";
        String tokenHash = TokenUtils.sha256(rawToken);
        InviteToken inviteToken = new InviteToken(tokenHash, testTaskList, testUser,
                LocalDateTime.now().plusHours(12));

        ListResponse expectedResponse = ListResponse.builder()
                .id(10L).name("TestList").role("ADMIN").build();

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(inviteToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListUserRepository.findByIdListIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(testTaskListUser));
        when(taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN)).thenReturn(expectedResponse);

        ListResponse result = taskListService.acceptInvite(rawToken, 1L);

        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(taskListUserRepository, never()).save(any(TaskListUser.class));
    }

    @Test
    void acceptInvite_WithExpiredToken_ThrowsTokenExpiredException() {
        String rawToken = "expired-accept-token";
        String tokenHash = TokenUtils.sha256(rawToken);
        InviteToken inviteToken = new InviteToken(tokenHash, testTaskList, testUser,
                LocalDateTime.now().minusHours(1));

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(inviteToken));

        assertThatThrownBy(() -> taskListService.acceptInvite(rawToken, 2L))
                .isInstanceOf(TokenExpiredException.class);

        verify(taskListUserRepository, never()).save(any());
    }

    @Test
    void acceptInvite_WithInvalidToken_ThrowsTokenExpiredException() {
        String rawToken = "nonexistent-token";
        String tokenHash = TokenUtils.sha256(rawToken);

        when(inviteTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskListService.acceptInvite(rawToken, 1L))
                .isInstanceOf(TokenExpiredException.class);
    }

}
