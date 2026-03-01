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
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TaskListMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

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
        when(taskListUserRepository.save(any(TaskListUser.class))).thenReturn(testTaskListUser);
        when(taskListMapper.toResponse(testTaskList, TaskListRole.USER)).thenReturn(expectedResponse);

        ListResponse result = taskListService.joinList("TestList", "pass123", 2L);

        assertThat(result).isNotNull();
        assertThat(result.getRole()).isEqualTo("USER");
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
        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 1L)).thenReturn(true);
        doNothing().when(todoRepository).deletePrivateTodosByListIdAndUserId(10L, 1L);
        doNothing().when(taskListUserRepository).deleteByListIdAndUserId(10L, 1L);

        taskListService.leaveList(10L, 1L);

        verify(todoRepository).deletePrivateTodosByListIdAndUserId(10L, 1L);
        verify(taskListUserRepository).deleteByListIdAndUserId(10L, 1L);
    }

    @Test
    void leaveList_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(taskListUserRepository.existsByIdListIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> taskListService.leaveList(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");

        verify(taskListUserRepository, never()).deleteByListIdAndUserId(anyLong(), anyLong());
    }
}
