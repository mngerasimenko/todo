package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TodoServiceImplTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskListRepository taskListRepository;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private TodoMapper todoMapper;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private SuggestionService suggestionService;

    @InjectMocks
    private TodoServiceImpl todoService;

    private User testUser;
    private TaskList testTaskList;
    private Todo testTodo;
    private TodoDto testTodoDto;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");

        testTaskList = new TaskList("TestList", testUser);
        testTaskList.setId(1L);

        testTodo = new Todo();
        testTodo.setId(1L);
        testTodo.setName("Test Todo");
        testTodo.setDone(false);
        testTodo.setCreatedAt(LocalDateTime.now());
        testTodo.setUser(testUser);
        testTodo.setTaskList(testTaskList);

        testTodoDto = new TodoDto();
        testTodoDto.setId(1L);
        testTodoDto.setName("Test Todo");
        testTodoDto.setDone(false);
        testTodoDto.setUserId(1L);
        testTodoDto.setListId(1L);
    }

    @Test
    void createTodo_WithValidDto_ReturnsCreatedTodoDto() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setListId(1L);

        Todo newTodo = new Todo();
        newTodo.setName("New Todo");
        newTodo.setDone(false);
        newTodo.setUser(testUser);
        newTodo.setTaskList(testTaskList);

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("New Todo");
        savedTodo.setDone(false);
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setTaskList(testTaskList);

        TodoDto savedTodoDto = new TodoDto();
        savedTodoDto.setId(2L);
        savedTodoDto.setName("New Todo");
        savedTodoDto.setDone(false);
        savedTodoDto.setUserId(1L);
        savedTodoDto.setListId(1L);
        savedTodoDto.setCreatedAt(savedTodo.getCreatedAt());

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(true);
        when(todoMapper.toEntity(newTodoDto)).thenReturn(newTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(savedTodo);
        when(todoMapper.toDto(savedTodo)).thenReturn(savedTodoDto);

        TodoDto result = todoService.createTodo(newTodoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(2L);
        assertThat(result.getName()).isEqualTo("New Todo");
        assertThat(result.getDone()).isFalse();
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getCreatedAt()).isNotNull();
        verify(userRepository, times(1)).findById(1L);
        verify(taskListRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(any(Todo.class));
        verify(todoMapper, times(1)).toDto(savedTodo);
        // R-6 хук: публичная задача попадает в словарь подсказок с private=false.
        verify(suggestionService, times(1)).track("New Todo", false);
    }

    @Test
    void createTodo_PrivateTodo_PassesPrivateTrueToSuggestionTracking() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("Секретное");
        newTodoDto.setUserId(1L);
        newTodoDto.setListId(1L);

        Todo newTodo = new Todo();
        newTodo.setName("Секретное");

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("Секретное");
        savedTodo.setIsPrivate(true);
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setTaskList(testTaskList);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(true);
        when(todoMapper.toEntity(newTodoDto)).thenReturn(newTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(savedTodo);
        when(todoMapper.toDto(savedTodo)).thenReturn(new TodoDto());

        todoService.createTodo(newTodoDto);

        // Приватная задача: хук обязан передать private=true (фактический skip — внутри track).
        verify(suggestionService, times(1)).track("Секретное", true);
    }

    @Test
    void createTodo_WhenSuggestionTrackingFails_StillReturnsDto() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setListId(1L);

        Todo newTodo = new Todo();
        newTodo.setName("New Todo");

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("New Todo");
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setTaskList(testTaskList);

        TodoDto savedTodoDto = new TodoDto();
        savedTodoDto.setId(2L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(true);
        when(todoMapper.toEntity(newTodoDto)).thenReturn(newTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(savedTodo);
        when(todoMapper.toDto(savedTodo)).thenReturn(savedTodoDto);
        doThrow(new RuntimeException("dictionary down"))
                .when(suggestionService).track(anyString(), anyBoolean());

        // Сбой словаря подсказок НЕ должен ломать создание задачи (best-effort).
        TodoDto result = todoService.createTodo(newTodoDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(2L);
    }

    @Test
    void createTodo_WithNonExistentUser_ThrowsUserNotFoundException() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(999L);
        newTodoDto.setListId(1L);

        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.createTodo(newTodoDto))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
        verify(todoMapper, never()).toEntity(any());
    }

    @Test
    void createTodo_WhenUserNotMember_ThrowsIllegalArgumentException() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setListId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> todoService.createTodo(newTodoDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Пользователь не является участником данного списка");

        verify(todoRepository, never()).save(any(Todo.class));
        verify(todoMapper, never()).toEntity(any());
    }

    @Test
    void createTodo_SetsDefaultValues() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setListId(1L);
        newTodoDto.setDone(true);

        Todo newTodo = new Todo();
        newTodo.setName("New Todo");

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("New Todo");
        savedTodo.setDone(false);
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setTaskList(testTaskList);

        TodoDto savedTodoDto = new TodoDto();
        savedTodoDto.setId(2L);
        savedTodoDto.setName("New Todo");
        savedTodoDto.setDone(false);
        savedTodoDto.setUserId(1L);
        savedTodoDto.setListId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(true);
        when(todoMapper.toEntity(newTodoDto)).thenReturn(newTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(savedTodo);
        when(todoMapper.toDto(savedTodo)).thenReturn(savedTodoDto);

        TodoDto result = todoService.createTodo(newTodoDto);

        assertThat(result.getDone()).isFalse();
        verify(todoRepository, times(1)).save(any(Todo.class));
    }

    @Test
    void updateTodo_WithValidIdAndDto_ReturnsUpdatedTodoDto() {
        TodoDto updateDto = new TodoDto();
        updateDto.setName("Updated Todo");
        updateDto.setDone(true);
        updateDto.setUserId(testUser.getId());

        Todo existingTodo = new Todo();
        existingTodo.setId(1L);
        existingTodo.setName("Old Todo");
        existingTodo.setDone(false);
        existingTodo.setCreatedAt(LocalDateTime.of(2026, 2, 11, 0, 0));
        existingTodo.setUser(testUser);
        existingTodo.setTaskList(testTaskList);

        Todo updatedTodo = new Todo();
        updatedTodo.setId(1L);
        updatedTodo.setName("Updated Todo");
        updatedTodo.setDone(true);
        updatedTodo.setCreatedAt(LocalDateTime.now());
        updatedTodo.setUser(testUser);

        TodoDto updatedTodoDto = new TodoDto();
        updatedTodoDto.setId(1L);
        updatedTodoDto.setName("Updated Todo");
        updatedTodoDto.setDone(true);
        updatedTodoDto.setUserId(testUser.getId());
        updatedTodoDto.setCreatedAt(updatedTodo.getCreatedAt());

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        doNothing().when(todoMapper).updateEntityFromDto(updateDto, existingTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(updatedTodo);
        when(todoMapper.toDto(updatedTodo)).thenReturn(updatedTodoDto);

        TodoDto result = todoService.updateTodo(1L, updateDto, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Updated Todo");
        assertThat(result.getDone()).isTrue();
        verify(userRepository, never()).findById(any());
        verify(todoMapper, times(1)).updateEntityFromDto(updateDto, existingTodo);
        verify(todoRepository, times(1)).save(existingTodo);
    }

    @Test
    void updateTodo_WithNonExistentId_ThrowsTodoNotFoundException() {
        TodoDto updateDto = new TodoDto();
        updateDto.setName("Updated Todo");

        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.updateTodo(999L, updateDto, 1L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void updateTodo_WithChangedUserIdAndNonExistentUser_ThrowsUserNotFoundException() {
        TodoDto updateDto = new TodoDto();
        updateDto.setName("Updated Todo");
        updateDto.setUserId(999L);

        Todo existingTodo = new Todo();
        existingTodo.setId(1L);
        existingTodo.setName("Old Todo");
        existingTodo.setUser(testUser);
        existingTodo.setTaskList(testTaskList);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.updateTodo(1L, updateDto, 1L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void updateTodo_UpdatesUserIfUserIdChanged() {
        User newUser = new User();
        newUser.setId(2L);
        newUser.setName("newuser");

        TodoDto updateDto = new TodoDto();
        updateDto.setName("Updated Todo");
        updateDto.setUserId(2L);

        Todo existingTodo = new Todo();
        existingTodo.setId(1L);
        existingTodo.setUser(testUser);
        existingTodo.setTaskList(testTaskList);

        Todo updatedTodo = new Todo();
        updatedTodo.setId(1L);
        updatedTodo.setUser(newUser);

        TodoDto updatedTodoDto = new TodoDto();
        updatedTodoDto.setId(1L);
        updatedTodoDto.setUserId(newUser.getId());

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(newUser));
        doNothing().when(todoMapper).updateEntityFromDto(updateDto, existingTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(updatedTodo);
        when(todoMapper.toDto(updatedTodo)).thenReturn(updatedTodoDto);

        TodoDto result = todoService.updateTodo(1L, updateDto, 1L);

        assertThat(result.getUserId()).isEqualTo(2L);
        verify(userRepository, times(1)).findById(2L);
        verify(todoMapper, times(1)).updateEntityFromDto(updateDto, existingTodo);
    }

    @Test
    void getTodoById_WithValidId_ReturnsTodoDto() {
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);

        TodoDto result = todoService.getTodoById(1L, 1L);

        assertThat(result).isEqualTo(testTodoDto);
        verify(todoRepository, times(1)).findById(1L);
        verify(todoMapper, times(1)).toDto(testTodo);
    }

    @Test
    void getTodoById_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.getTodoById(999L, 1L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoMapper, never()).toDto(any(Todo.class));
    }

    @Test
    void getTodoById_WithNonMember_ThrowsAccessDeniedException() {
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.getTodoById(1L, 99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAllTodos_ReturnsListOfTodoDtos() {
        Todo todo2 = new Todo();
        todo2.setId(2L);
        todo2.setName("Todo 2");

        TodoDto dto2 = new TodoDto();
        dto2.setId(2L);
        dto2.setName("Todo 2");

        when(taskListUserRepository.findListIdsByUserId(1L)).thenReturn(List.of(1L));
        when(todoRepository.findByListIdsVisibleToUser(List.of(1L), 1L))
                .thenReturn(Arrays.asList(testTodo, todo2));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(todo2)).thenReturn(dto2);

        List<TodoDto> result = todoService.getAllTodos(1L);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(testTodoDto, dto2);
    }

    @Test
    void getAllTodos_WithNoLists_ReturnsEmptyList() {
        when(taskListUserRepository.findListIdsByUserId(1L)).thenReturn(Collections.emptyList());

        List<TodoDto> result = todoService.getAllTodos(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getFilteredTodosByUserId_WithValidUserIdAndFilter_ReturnsFilteredTodos() {
        Todo matchingTodo = new Todo();
        matchingTodo.setId(2L);
        matchingTodo.setName("Test Task");

        TodoDto matchingDto = new TodoDto();
        matchingDto.setId(2L);
        matchingDto.setName("Test Task");

        when(todoRepository.findAllByUserIdAndNameContainingIgnoreCase(1L, "test"))
                .thenReturn(Arrays.asList(testTodo, matchingTodo));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(matchingTodo)).thenReturn(matchingDto);

        List<TodoDto> result = todoService.getFilteredTodosByUserId(1L, "test");

        assertThat(result).hasSize(2);
        verify(todoRepository, times(1))
                .findAllByUserIdAndNameContainingIgnoreCase(1L, "test");
    }

    @Test
    void getFilteredTodosByUserId_WithNoMatches_ReturnsEmptyList() {
        when(todoRepository.findAllByUserIdAndNameContainingIgnoreCase(1L, "nonexistent"))
                .thenReturn(Collections.emptyList());

        List<TodoDto> result = todoService.getFilteredTodosByUserId(1L, "nonexistent");

        assertThat(result).isEmpty();
        verify(todoRepository, times(1))
                .findAllByUserIdAndNameContainingIgnoreCase(1L, "nonexistent");
    }

    @Test
    void getTodosByUserId_WithSameUser_ReturnsAllTodos() {
        Todo userTodo2 = new Todo();
        userTodo2.setId(2L);
        userTodo2.setName("User Todo 2");

        TodoDto dto2 = new TodoDto();
        dto2.setId(2L);
        dto2.setName("User Todo 2");

        when(todoRepository.findByUserId(1L)).thenReturn(Arrays.asList(testTodo, userTodo2));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(userTodo2)).thenReturn(dto2);

        List<TodoDto> result = todoService.getTodosByUserId(1L, 1L);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(testTodoDto, dto2);
        verify(todoRepository, times(1)).findByUserId(1L);
    }

    @Test
    void getTodosByUserId_WithDifferentUser_FiltersPrivateTodos() {
        Todo privateTodo = new Todo();
        privateTodo.setId(2L);
        privateTodo.setName("Private Todo");
        privateTodo.setIsPrivate(true);

        Todo publicTodo = new Todo();
        publicTodo.setId(3L);
        publicTodo.setName("Public Todo");
        publicTodo.setIsPrivate(false);

        TodoDto publicDto = new TodoDto();
        publicDto.setId(3L);
        publicDto.setName("Public Todo");

        when(todoRepository.findByUserId(1L)).thenReturn(Arrays.asList(privateTodo, publicTodo));
        when(todoMapper.toDto(publicTodo)).thenReturn(publicDto);

        List<TodoDto> result = todoService.getTodosByUserId(1L, 99L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Public Todo");
    }

    @Test
    void getTodosByUserId_WithNoTodos_ReturnsEmptyList() {
        when(todoRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

        List<TodoDto> result = todoService.getTodosByUserId(1L, 1L);

        assertThat(result).isEmpty();
        verify(todoRepository, times(1)).findByUserId(1L);
    }

    @Test
    void getActiveTodosByUserId_WithValidUserId_ReturnsActiveTodos() {
        Todo activeTodo = new Todo();
        activeTodo.setId(2L);
        activeTodo.setName("Active Todo");
        activeTodo.setDone(false);

        TodoDto activeDto = new TodoDto();
        activeDto.setId(2L);
        activeDto.setName("Active Todo");
        activeDto.setDone(false);

        when(todoRepository.findByUserIdAndDone(1L, false))
                .thenReturn(Arrays.asList(testTodo, activeTodo));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(activeTodo)).thenReturn(activeDto);

        List<TodoDto> result = todoService.getActiveTodosByUserId(1L);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(todo -> !todo.getDone());
        verify(todoRepository, times(1)).findByUserIdAndDone(1L, false);
    }

    @Test
    void getCompletedTodosByUserId_WithValidUserId_ReturnsCompletedTodos() {
        Todo completedTodo = new Todo();
        completedTodo.setId(2L);
        completedTodo.setName("Completed Todo");
        completedTodo.setDone(true);

        TodoDto completedDto = new TodoDto();
        completedDto.setId(2L);
        completedDto.setName("Completed Todo");
        completedDto.setDone(true);

        when(todoRepository.findByUserIdAndDone(1L, true))
                .thenReturn(Arrays.asList(completedTodo));
        when(todoMapper.toDto(completedTodo)).thenReturn(completedDto);

        List<TodoDto> result = todoService.getCompletedTodosByUserId(1L);

        assertThat(result).hasSize(1);
        assertThat(result).allMatch(TodoDto::getDone);
        verify(todoRepository, times(1)).findByUserIdAndDone(1L, true);
    }

    @Test
    void deleteTodo_WithValidId_DeletesTodo() {
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));

        todoService.deleteTodo(1L, 1L);

        verify(todoRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).deleteById(1L);
    }

    @Test
    void deleteTodo_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.deleteTodo(999L, 1L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteTodosByUserId_DeletesAllUserTodos() {
        todoService.deleteTodosByUserId(1L);

        verify(todoRepository, times(1)).deleteByUserId(1L);
    }

    @Test
    void markAsDone_WithValidId_MarksTodoAsDone() {
        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(false);
        todoToMark.setTaskList(testTaskList);
        todoToMark.setUser(testUser);

        Todo markedTodo = new Todo();
        markedTodo.setId(1L);
        markedTodo.setName("Todo");
        markedTodo.setDone(true);
        markedTodo.setCreatedAt(LocalDateTime.now());
        markedTodo.setCompletedAt(LocalDateTime.now());

        TodoDto markedDto = new TodoDto();
        markedDto.setId(1L);
        markedDto.setName("Todo");
        markedDto.setDone(true);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(todoToMark));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(todoRepository.save(any(Todo.class))).thenReturn(markedTodo);
        when(todoMapper.toDto(markedTodo)).thenReturn(markedDto);

        TodoDto result = todoService.markAsDone(1L, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getDone()).isTrue();
        verify(todoRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(todoToMark);
        assertThat(todoToMark.isDone()).isTrue();
        assertThat(todoToMark.getCompletedAt()).isNotNull();
    }

    @Test
    void markAsDone_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsDone(999L, 1L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void markAsDone_WithCompletorUserId_SetsCompletorUser() {
        User completor = new User();
        completor.setId(2L);
        completor.setName("completor");

        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(false);
        todoToMark.setTaskList(testTaskList);
        todoToMark.setUser(testUser);

        Todo markedTodo = new Todo();
        markedTodo.setId(1L);
        markedTodo.setName("Todo");
        markedTodo.setDone(true);
        markedTodo.setCompletedAt(LocalDateTime.now());
        markedTodo.setCompletorUser(completor);

        TodoDto markedDto = new TodoDto();
        markedDto.setId(1L);
        markedDto.setName("Todo");
        markedDto.setDone(true);
        markedDto.setCompletorUserId(2L);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(todoToMark));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 2L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, completor, TaskListRole.USER)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(completor));
        when(todoRepository.save(any(Todo.class))).thenReturn(markedTodo);
        when(todoMapper.toDto(markedTodo)).thenReturn(markedDto);

        TodoDto result = todoService.markAsDone(1L, 2L);

        assertThat(result).isNotNull();
        assertThat(result.getDone()).isTrue();
        assertThat(result.getCompletorUserId()).isEqualTo(2L);
        verify(userRepository, times(1)).findById(2L);
        assertThat(todoToMark.getCompletorUser()).isEqualTo(completor);
    }

    @Test
    void markAsUndone_WithValidId_MarksTodoAsUndone() {
        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(true);
        todoToMark.setCompletedAt(LocalDateTime.now());
        todoToMark.setTaskList(testTaskList);
        todoToMark.setUser(testUser);

        Todo markedTodo = new Todo();
        markedTodo.setId(1L);
        markedTodo.setName("Todo");
        markedTodo.setDone(false);
        markedTodo.setCreatedAt(LocalDateTime.now());

        TodoDto markedDto = new TodoDto();
        markedDto.setId(1L);
        markedDto.setName("Todo");
        markedDto.setDone(false);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(todoToMark));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        when(todoRepository.save(any(Todo.class))).thenReturn(markedTodo);
        when(todoMapper.toDto(markedTodo)).thenReturn(markedDto);

        TodoDto result = todoService.markAsUndone(1L, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getDone()).isFalse();
        verify(todoRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(todoToMark);
        assertThat(todoToMark.isDone()).isFalse();
        assertThat(todoToMark.getCompletedAt()).isNull();
        assertThat(todoToMark.getCompletorUser()).isNull();
    }

    @Test
    void markAsUndone_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsUndone(999L, 1L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    // --- Тесты проверки принадлежности к списку ---

    @Test
    void updateTodo_WhenUserNotMember_ThrowsAccessDeniedException() {
        Todo existingTodo = new Todo();
        existingTodo.setId(1L);
        existingTodo.setName("Todo");
        existingTodo.setUser(testUser);
        existingTodo.setTaskList(testTaskList);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.updateTodo(1L, testTodoDto, 99L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("не является участником");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void deleteTodo_WhenUserNotMember_ThrowsAccessDeniedException() {
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.deleteTodo(1L, 99L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("не является участником");

        verify(todoRepository, never()).deleteById(anyLong());
    }

    @Test
    void markAsDone_WhenUserNotMember_ThrowsAccessDeniedException() {
        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(false);
        todoToMark.setTaskList(testTaskList);
        todoToMark.setUser(testUser);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(todoToMark));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsDone(1L, 99L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("не является участником");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void markAsUndone_WhenUserNotMember_ThrowsAccessDeniedException() {
        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(true);
        todoToMark.setTaskList(testTaskList);
        todoToMark.setUser(testUser);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(todoToMark));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsUndone(1L, 99L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("не является участником");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    // ========== Тесты на проверку владельца и ADMIN ==========

    @Test
    void deleteTodo_WhenUserIsOwner_DeletesSuccessfully() {
        // Пользователь владеет задачей
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));

        todoService.deleteTodo(1L, 1L);

        verify(todoRepository).deleteById(1L);
    }

    @Test
    void deleteTodo_WhenUserIsAdmin_DeletesSuccessfully() {
        // Пользователь — ADMIN списка, но не владелец задачи
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);

        TaskListUser adminMembership = new TaskListUser();
        adminMembership.setRole(TaskListRole.ADMIN);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(adminMembership));

        todoService.deleteTodo(1L, 1L);

        verify(todoRepository).deleteById(1L);
    }

    @Test
    void deleteTodo_WhenUserIsNotOwnerNotAdmin_ThrowsAccessDeniedException() {
        // Пользователь — USER, не владелец задачи
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);

        TaskListUser userMembership = new TaskListUser();
        userMembership.setRole(TaskListRole.USER);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(userMembership));

        assertThatThrownBy(() -> todoService.deleteTodo(1L, 1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Только создатель задачи или администратор списка могут изменить эту задачу");

        verify(todoRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteTodo_WhenPrivateTodoNotOwner_ThrowsAccessDeniedException() {
        // Приватная задача, пользователь не владелец
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);
        testTodo.setIsPrivate(true);

        TaskListUser adminMembership = new TaskListUser();
        adminMembership.setRole(TaskListRole.ADMIN);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(adminMembership));

        assertThatThrownBy(() -> todoService.deleteTodo(1L, 1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Приватные задачи доступны только их создателю");

        verify(todoRepository, never()).deleteById(anyLong());
    }

    @Test
    void updateTodo_WhenUserIsNotOwnerNotAdmin_ThrowsAccessDeniedException() {
        // Пользователь — USER, не владелец задачи
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);

        TaskListUser userMembership = new TaskListUser();
        userMembership.setRole(TaskListRole.USER);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(userMembership));

        assertThatThrownBy(() -> todoService.updateTodo(1L, testTodoDto, 1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Только создатель задачи или администратор списка могут изменить эту задачу");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void updateTodo_WhenUserIsAdmin_UpdatesSuccessfully() {
        // ADMIN списка может редактировать чужую публичную задачу
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);

        TaskListUser adminMembership = new TaskListUser();
        adminMembership.setRole(TaskListRole.ADMIN);

        TodoDto updateDto = new TodoDto();
        updateDto.setName("Updated");
        updateDto.setDone(false);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(adminMembership));
        doNothing().when(todoMapper).updateEntityFromDto(updateDto, testTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(testTodo);
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);

        TodoDto result = todoService.updateTodo(1L, updateDto, 1L);

        assertThat(result).isNotNull();
        verify(todoRepository).save(any(Todo.class));
    }

    // ========== Тесты на коллаборацию (отметка выполнения любым участником) ==========

    @Test
    void markAsDone_WhenUserIsNotOwnerButIsUser_MarksAsDone() {
        // Пользователь — USER (не ADMIN, не владелец), может отметить чужую задачу
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);

        TaskListUser userMembership = new TaskListUser();
        userMembership.setRole(TaskListRole.USER);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(userMembership));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        todoService.markAsDone(1L, 1L);

        verify(todoRepository).save(testTodo);
        assertThat(testTodo.isDone()).isTrue();
    }

    @Test
    void markAsUndone_WhenUserIsNotOwnerButIsUser_MarksAsUndone() {
        // Пользователь — USER (не ADMIN, не владелец), может снять отметку с чужой задачи
        User otherUser = new User();
        otherUser.setId(2L);
        testTodo.setUser(otherUser);
        testTodo.setDone(true);

        TaskListUser userMembership = new TaskListUser();
        userMembership.setRole(TaskListRole.USER);

        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(taskListUserRepository.findByIdListIdAndIdUserId(1L, 1L))
                .thenReturn(Optional.of(userMembership));

        todoService.markAsUndone(1L, 1L);

        verify(todoRepository).save(testTodo);
        assertThat(testTodo.isDone()).isFalse();
    }
}
