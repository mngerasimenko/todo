package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.Account;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.AccountRepository;
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
    private AccountRepository accountRepository;

    @Mock
    private TodoMapper todoMapper;

    @InjectMocks
    private TodoServiceImpl todoService;

    private User testUser;
    private Account testAccount;
    private Todo testTodo;
    private TodoDto testTodoDto;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");

        testAccount = new Account("TestAccount", "$2a$10$hashedPass");
        testAccount.setId(1L);

        testTodo = new Todo();
        testTodo.setId(1L);
        testTodo.setName("Test Todo");
        testTodo.setDone(false);
        testTodo.setCreatedAt(LocalDateTime.now());
        testTodo.setUser(testUser);
        testTodo.setAccount(testAccount);

        testTodoDto = new TodoDto();
        testTodoDto.setId(1L);
        testTodoDto.setName("Test Todo");
        testTodoDto.setDone(false);
        testTodoDto.setUserId(1L);
        testTodoDto.setAccountId(1L);
    }

    @Test
    void createTodo_WithValidDto_ReturnsCreatedTodoDto() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setAccountId(1L);

        Todo newTodo = new Todo();
        newTodo.setName("New Todo");
        newTodo.setDone(false);
        newTodo.setUser(testUser);
        newTodo.setAccount(testAccount);

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("New Todo");
        savedTodo.setDone(false);
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setAccount(testAccount);

        TodoDto savedTodoDto = new TodoDto();
        savedTodoDto.setId(2L);
        savedTodoDto.setName("New Todo");
        savedTodoDto.setDone(false);
        savedTodoDto.setUserId(1L);
        savedTodoDto.setAccountId(1L);
        savedTodoDto.setCreatedAt(savedTodo.getCreatedAt());

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(testAccount));
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
        verify(accountRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(any(Todo.class));
        verify(todoMapper, times(1)).toDto(savedTodo);
    }

    @Test
    void createTodo_WithNonExistentUser_ThrowsUserNotFoundException() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(999L);
        newTodoDto.setAccountId(1L);

        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.createTodo(newTodoDto))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
        verify(todoMapper, never()).toEntity(any());
    }

    @Test
    void createTodo_SetsDefaultValues() {
        TodoDto newTodoDto = new TodoDto();
        newTodoDto.setName("New Todo");
        newTodoDto.setUserId(1L);
        newTodoDto.setAccountId(1L);
        newTodoDto.setDone(true);

        Todo newTodo = new Todo();
        newTodo.setName("New Todo");

        Todo savedTodo = new Todo();
        savedTodo.setId(2L);
        savedTodo.setName("New Todo");
        savedTodo.setDone(false);
        savedTodo.setCreatedAt(LocalDateTime.now());
        savedTodo.setUser(testUser);
        savedTodo.setAccount(testAccount);

        TodoDto savedTodoDto = new TodoDto();
        savedTodoDto.setId(2L);
        savedTodoDto.setName("New Todo");
        savedTodoDto.setDone(false);
        savedTodoDto.setUserId(1L);
        savedTodoDto.setAccountId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(testAccount));
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
        doNothing().when(todoMapper).updateEntityFromDto(updateDto, existingTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(updatedTodo);
        when(todoMapper.toDto(updatedTodo)).thenReturn(updatedTodoDto);

        TodoDto result = todoService.updateTodo(1L, updateDto);

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

        assertThatThrownBy(() -> todoService.updateTodo(999L, updateDto))
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

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.updateTodo(1L, updateDto))
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

        Todo updatedTodo = new Todo();
        updatedTodo.setId(1L);
        updatedTodo.setUser(newUser);

        TodoDto updatedTodoDto = new TodoDto();
        updatedTodoDto.setId(1L);
        updatedTodoDto.setUserId(newUser.getId());

        when(todoRepository.findById(1L)).thenReturn(Optional.of(existingTodo));
        when(userRepository.findById(2L)).thenReturn(Optional.of(newUser));
        doNothing().when(todoMapper).updateEntityFromDto(updateDto, existingTodo);
        when(todoRepository.save(any(Todo.class))).thenReturn(updatedTodo);
        when(todoMapper.toDto(updatedTodo)).thenReturn(updatedTodoDto);

        TodoDto result = todoService.updateTodo(1L, updateDto);

        assertThat(result.getUserId()).isEqualTo(2L);
        verify(userRepository, times(1)).findById(2L);
        verify(todoMapper, times(1)).updateEntityFromDto(updateDto, existingTodo);
    }

    @Test
    void getTodoById_WithValidId_ReturnsTodoDto() {
        when(todoRepository.findById(1L)).thenReturn(Optional.of(testTodo));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);

        TodoDto result = todoService.getTodoById(1L);

        assertThat(result).isEqualTo(testTodoDto);
        verify(todoRepository, times(1)).findById(1L);
        verify(todoMapper, times(1)).toDto(testTodo);
    }

    @Test
    void getTodoById_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.getTodoById(999L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoMapper, never()).toDto(any(Todo.class));
    }

    @Test
    void getAllTodos_ReturnsListOfTodoDtos() {
        Todo todo2 = new Todo();
        todo2.setId(2L);
        todo2.setName("Todo 2");

        TodoDto dto2 = new TodoDto();
        dto2.setId(2L);
        dto2.setName("Todo 2");

        when(todoRepository.findAll()).thenReturn(Arrays.asList(testTodo, todo2));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(todo2)).thenReturn(dto2);

        List<TodoDto> result = todoService.getAllTodos();

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(testTodoDto, dto2);
        verify(todoRepository, times(1)).findAll();
        verify(todoMapper, times(1)).toDto(testTodo);
        verify(todoMapper, times(1)).toDto(todo2);
    }

    @Test
    void getAllTodos_WithEmptyRepository_ReturnsEmptyList() {
        when(todoRepository.findAll()).thenReturn(Collections.emptyList());

        List<TodoDto> result = todoService.getAllTodos();

        assertThat(result).isEmpty();
        verify(todoRepository, times(1)).findAll();
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
    void getTodosByUserId_WithValidUserId_ReturnsUserTodos() {
        Todo userTodo2 = new Todo();
        userTodo2.setId(2L);
        userTodo2.setName("User Todo 2");

        TodoDto dto2 = new TodoDto();
        dto2.setId(2L);
        dto2.setName("User Todo 2");

        when(todoRepository.findByUserId(1L)).thenReturn(Arrays.asList(testTodo, userTodo2));
        when(todoMapper.toDto(testTodo)).thenReturn(testTodoDto);
        when(todoMapper.toDto(userTodo2)).thenReturn(dto2);

        List<TodoDto> result = todoService.getTodosByUserId(1L);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(testTodoDto, dto2);
        verify(todoRepository, times(1)).findByUserId(1L);
    }

    @Test
    void getTodosByUserId_WithNoTodos_ReturnsEmptyList() {
        when(todoRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

        List<TodoDto> result = todoService.getTodosByUserId(1L);

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
        when(todoRepository.existsById(1L)).thenReturn(true);

        todoService.deleteTodo(1L);

        verify(todoRepository, times(1)).existsById(1L);
        verify(todoRepository, times(1)).deleteById(1L);
    }

    @Test
    void deleteTodo_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> todoService.deleteTodo(999L))
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
        when(todoRepository.save(any(Todo.class))).thenReturn(markedTodo);
        when(todoMapper.toDto(markedTodo)).thenReturn(markedDto);

        TodoDto result = todoService.markAsDone(1L);

        assertThat(result).isNotNull();
        assertThat(result.getDone()).isTrue();
        verify(todoRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(todoToMark);
        assertThat(todoToMark.isDone()).isTrue(); // Проверяем, что поле изменено
        assertThat(todoToMark.getCompletedAt()).isNotNull(); // completedAt должен быть установлен
    }

    @Test
    void markAsDone_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsDone(999L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void markAsUndone_WithValidId_MarksTodoAsUndone() {
        Todo todoToMark = new Todo();
        todoToMark.setId(1L);
        todoToMark.setName("Todo");
        todoToMark.setDone(true);
        todoToMark.setCompletedAt(LocalDateTime.now());

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
        when(todoRepository.save(any(Todo.class))).thenReturn(markedTodo);
        when(todoMapper.toDto(markedTodo)).thenReturn(markedDto);

        TodoDto result = todoService.markAsUndone(1L);

        assertThat(result).isNotNull();
        assertThat(result.getDone()).isFalse();
        verify(todoRepository, times(1)).findById(1L);
        verify(todoRepository, times(1)).save(todoToMark);
        assertThat(todoToMark.isDone()).isFalse(); // Проверяем, что поле изменено
        assertThat(todoToMark.getCompletedAt()).isNull(); // completedAt должен быть очищен
        assertThat(todoToMark.getCompletorUser()).isNull(); // completorUser должен быть очищен
    }

    @Test
    void markAsUndone_WithNonExistentId_ThrowsTodoNotFoundException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.markAsUndone(999L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("Todo not found with id: 999");

        verify(todoRepository, never()).save(any(Todo.class));
    }
}
