package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import ru.mngerasimenko.todolist.dto.DueTodosResponse;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.ReminderScope;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

    @Mock
    private FeatureFlagStore flagStore;

    @InjectMocks
    private TodoServiceImpl todoService;

    private User testUser;
    private TaskList testTaskList;
    private Todo testTodo;
    private TodoDto testTodoDto;

    @BeforeEach
    void setUp() {
        // Прод-дефолт флага — true. Без этого стаба мок отдавал бы false, и весь набор
        // createTodo-тестов молча гонял бы только ветку «механизм выключен».
        lenient().when(flagStore.isEnabled(FeatureFlag.TODO_CREATE_DEDUPE)).thenReturn(true);

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
        // R-6 хук: публичная задача попадает в словарь подсказок с private=false и id автора.
        verify(suggestionService, times(1)).track("New Todo", false, 1L);
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

        // Приватная задача: хук обязан передать private=true и id автора (skip — внутри track).
        verify(suggestionService, times(1)).track("Секретное", true, 1L);
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
                .when(suggestionService).track(anyString(), anyBoolean(), anyLong());

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

    // --- Тесты правил срока (applyDueRules) ---

    @Test
    void createTodo_PrivateTodo_ForcesScopeSelf() {
        TodoDto dto = dueDto(LocalDate.of(2026, 7, 31));
        dto.setIsPrivate(true);
        dto.setReminderScope(ReminderScope.ALL);
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        todoService.createTodo(dto);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderScope()).isEqualTo(ReminderScope.SELF);
    }

    @Test
    void updateTodo_DueMomentChanged_ClearsReminderSentAt() {
        Todo existing = todoWithDue(LocalDate.of(2026, 7, 31));
        existing.setReminderSentAt(LocalDateTime.now().minusHours(1));
        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoDto dto = dueDto(LocalDate.of(2026, 8, 5));
        todoService.updateTodo(1L, dto, existing.getUserId());

        assertThat(existing.getReminderSentAt()).isNull();
    }

    @Test
    void updateTodo_DueCleared_ResetsRelatedFields() {
        Todo existing = todoWithDue(LocalDate.of(2026, 7, 31));
        existing.setRemindBeforeMinutes(1440);
        existing.setReminderScope(ReminderScope.ALL);
        existing.setReminderSentAt(LocalDateTime.now());
        when(todoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoDto dto = dueDto(null);
        todoService.updateTodo(1L, dto, existing.getUserId());

        assertThat(existing.getDueDate()).isNull();
        assertThat(existing.getDueTimezone()).isNull();
        assertThat(existing.getReminderSentAt()).isNull();
        assertThat(existing.getRemindBeforeMinutes()).isZero();
        assertThat(existing.getReminderScope()).isEqualTo(ReminderScope.SELF);
    }

    @Test
    void createTodo_NoTimezone_FallsBackToMoscow() {
        TodoDto dto = dueDto(LocalDate.of(2026, 7, 31));
        dto.setDueTimezone(null);
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        todoService.createTodo(dto);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getDueTimezone()).isEqualTo("Europe/Moscow");
    }

    @Test
    void createTodo_InvalidTimezone_FallsBackToMoscow() {
        TodoDto dto = dueDto(LocalDate.of(2026, 7, 31));
        dto.setDueTimezone("Not/AZone");
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        todoService.createTodo(dto);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getDueTimezone()).isEqualTo("Europe/Moscow");
    }

    @Test
    void createTodo_ValidNonMoscowTimezone_IsPreserved() {
        TodoDto dto = dueDto(LocalDate.of(2026, 7, 31));
        dto.setDueTimezone("Asia/Novosibirsk");
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        todoService.createTodo(dto);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getDueTimezone()).isEqualTo("Asia/Novosibirsk");
    }

    // ===== Идемпотентность создания по client_request_id (разбор инцидента 23.08.2026) =====

    @Test
    void createTodo_RetryWithSameClientRequestId_ReturnsExistingTodoWithoutSecondRow() {
        TodoDto dto = createDto("лук репчатый", KEY);
        Todo alreadyCreated = new Todo();
        alreadyCreated.setId(42L);
        alreadyCreated.setClientRequestId(KEY);
        TodoDto alreadyCreatedDto = new TodoDto();
        alreadyCreatedDto.setId(42L);
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, KEY))
                .thenReturn(Optional.of(alreadyCreated));
        when(todoMapper.toDto(alreadyCreated)).thenReturn(alreadyCreatedDto);

        TodoDto result = todoService.createTodo(dto);

        assertThat(result.getId()).isEqualTo(42L);
        // Ретрай не должен ни создавать строку, ни слать второй push, ни пополнять словарь,
        // ни жечь лимит подписки.
        verify(todoRepository, never()).save(any(Todo.class));
        verify(pushNotificationService, never()).notifyNewTodo(anyLong(), anyLong(), anyString(), anyString());
        verify(suggestionService, never()).track(anyString(), anyBoolean(), anyLong());
        verify(subscriptionService, never()).assertCanCreateTodo(anyLong(), anyLong());
    }

    @Test
    void createTodo_UnknownClientRequestId_CreatesTodoAndPersistsKey() {
        TodoDto dto = createDto("лук репчатый", KEY);
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, KEY))
                .thenReturn(Optional.empty());
        stubCreatePath();

        todoService.createTodo(dto);

        // Ловим DTO на входе настоящего маппера, а не результат собственного стаба: так тест
        // проверяет решение сервиса, а не то, что стаб копирует поле.
        ArgumentCaptor<TodoDto> captor = ArgumentCaptor.forClass(TodoDto.class);
        verify(todoMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isEqualTo(KEY);
    }

    @Test
    void createTodo_KeyWithSurroundingSpaces_IsTrimmedBeforeLookup() {
        TodoDto dto = createDto("лук репчатый", "  " + KEY + "  ");
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, KEY))
                .thenReturn(Optional.empty());
        stubCreatePath();

        todoService.createTodo(dto);

        ArgumentCaptor<TodoDto> captor = ArgumentCaptor.forClass(TodoDto.class);
        verify(todoMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isEqualTo(KEY);
    }

    @Test
    void createTodo_RetryWithDivergedList_StillReturnsCreatedTask() {
        // Ключ авторитетнее payload'а: повтор с другим списком не создаёт вторую задачу
        // и не переносит существующую — возвращается созданная, как есть.
        TodoDto dto = createDto("лук репчатый", KEY);
        dto.setListId(1L);
        Todo alreadyCreated = new Todo();
        alreadyCreated.setId(42L);
        alreadyCreated.setName("лук репчатый");
        alreadyCreated.setListId(999L);
        alreadyCreated.setClientRequestId(KEY);
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, KEY))
                .thenReturn(Optional.of(alreadyCreated));
        when(todoMapper.toDto(alreadyCreated)).thenReturn(new TodoDto());

        todoService.createTodo(dto);

        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void createTodo_TwoDeliberateAddsWithDifferentKeys_CreateTwoRows() {
        // Осознанное «две одинаковые задачи»: ключи разные, схлопывания быть не должно.
        TodoDto first = createDto("молоко", KEY);
        TodoDto second = createDto("молоко", OTHER_KEY);
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, KEY)).thenReturn(Optional.empty());
        when(todoRepository.findFirstByUserIdAndClientRequestIdOrderByIdAsc(1L, OTHER_KEY)).thenReturn(Optional.empty());
        stubCreatePath();

        todoService.createTodo(first);
        todoService.createTodo(second);

        verify(todoRepository, times(2)).save(any(Todo.class));
    }

    @Test
    void createTodo_NoClientRequestId_CreatesWithoutLookup() {
        // Старая сборка и веб ключ не шлют — поведение обязано остаться прежним.
        TodoDto dto = createDto("лук репчатый", null);
        stubCreatePath();

        todoService.createTodo(dto);

        verify(todoRepository, never()).findFirstByUserIdAndClientRequestIdOrderByIdAsc(anyLong(), any());
        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    void createTodo_BlankClientRequestId_TreatedAsAbsent() {
        TodoDto dto = createDto("лук репчатый", "   ");
        stubCreatePath();

        todoService.createTodo(dto);

        verify(todoRepository, never()).findFirstByUserIdAndClientRequestIdOrderByIdAsc(anyLong(), any());
        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isNull();
    }

    @Test
    void createTodo_FlagOff_SkipsLookupAndDoesNotPersistKey() {
        // Флаг обязан возвращать поведение «как было»: без записи ключа, иначе уникальный
        // индекс продолжил бы отбивать ретраи 409-ми при выключенном механизме.
        TodoDto dto = createDto("лук репчатый", KEY);
        when(flagStore.isEnabled(FeatureFlag.TODO_CREATE_DEDUPE)).thenReturn(false);
        stubCreatePath();

        todoService.createTodo(dto);

        verify(todoRepository, never()).findFirstByUserIdAndClientRequestIdOrderByIdAsc(anyLong(), any());
        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isNull();
    }

    private static final String KEY = "3f2b1c40-0000-4000-8000-000000000001";
    private static final String OTHER_KEY = "3f2b1c40-0000-4000-8000-000000000002";

    /** Минимальный DTO создания задачи с заданным ключом идемпотентности. */
    private TodoDto createDto(String name, String clientRequestId) {
        TodoDto dto = new TodoDto();
        dto.setName(name);
        dto.setUserId(1L);
        dto.setListId(1L);
        dto.setClientRequestId(clientRequestId);
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        lenient().when(taskListRepository.findById(1L)).thenReturn(Optional.of(testTaskList));
        lenient().when(taskListUserRepository.existsByIdListIdAndIdUserId(1L, 1L)).thenReturn(true);
        return dto;
    }

    /** Стабы хвоста создания — нужны только там, где ретрай НЕ распознан. */
    private void stubCreatePath() {
        lenient().when(todoMapper.toEntity(any(TodoDto.class))).thenAnswer(inv -> {
            TodoDto d = inv.getArgument(0);
            Todo entity = new Todo();
            entity.setName(d.getName());
            entity.setDone(false);
            entity.setIsPrivate(d.isPrivate());
            entity.setClientRequestId(d.getClientRequestId());
            return entity;
        });
        lenient().when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(todoMapper.toDto(any(Todo.class))).thenReturn(new TodoDto());
    }

    /**
     * DTO со сроком для тестов applyDueRules. Заодно лениво мокирует зависимости
     * пути создания (владелец/список/членство/маппер), чтобы не дублировать их
     * в каждом тесте — часть тестов их не использует, поэтому стабы lenient.
     */
    private TodoDto dueDto(LocalDate dueDate) {
        TodoDto dto = new TodoDto();
        dto.setName("Due Todo");
        dto.setUserId(testUser.getId());
        dto.setListId(testTaskList.getId());
        dto.setDone(false);
        dto.setDueDate(dueDate);
        dto.setDueTime(LocalTime.of(9, 0));
        dto.setDueTimezone("Europe/Moscow");
        dto.setRemindBeforeMinutes(0);
        dto.setReminderScope(ReminderScope.SELF);
        dto.setDueFieldsProvided(true);

        lenient().when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        lenient().when(taskListRepository.findById(testTaskList.getId())).thenReturn(Optional.of(testTaskList));
        lenient().when(taskListUserRepository.existsByIdListIdAndIdUserId(testTaskList.getId(), testUser.getId()))
                .thenReturn(true);
        lenient().when(todoMapper.toEntity(any(TodoDto.class))).thenAnswer(inv -> {
            TodoDto d = inv.getArgument(0);
            Todo entity = new Todo();
            entity.setName(d.getName());
            entity.setDone(d.isDone());
            entity.setIsPrivate(d.isPrivate());
            return entity;
        });
        return dto;
    }

    /**
     * Существующая задача со сроком, владельцем и списком — плюс мок членства
     * в списке, нужный assertCanModifyTodo внутри updateTodo.
     */
    private Todo todoWithDue(LocalDate dueDate) {
        Todo entity = new Todo();
        entity.setId(1L);
        entity.setName("Due Todo");
        entity.setDone(false);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUser(testUser);
        entity.setTaskList(testTaskList);
        entity.setDueDate(dueDate);
        entity.setDueTime(LocalTime.of(9, 0));
        entity.setDueTimezone("Europe/Moscow");
        entity.setRemindBeforeMinutes(0);
        entity.setReminderScope(ReminderScope.SELF);

        lenient().when(taskListUserRepository.findByIdListIdAndIdUserId(testTaskList.getId(), testUser.getId()))
                .thenReturn(Optional.of(new TaskListUser(testTaskList, testUser, TaskListRole.USER)));
        return entity;
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

    /**
     * Группировка обязана смотреть на пояс ЗАДАЧИ, а не сервера/JVM. Два фиксированных
     * пояса на разных концах суток (+14:00 и -12:00) гарантированно показывают разные
     * календарные даты «сегодня» в любой реальный момент времени — разница между ними 26
     * часов, а календарные сутки короче (24 часа), так что даты не могут совпасть. Это
     * позволяет тесту оставаться детерминированным независимо от пояса машины, на которой
     * он запускается: одна и та же дата due_date попадает в "today" для одной задачи и в
     * "upcoming" для другой — только из-за разного due_timezone у каждой.
     */
    @Test
    void getDueTodos_GroupsByTaskOwnTimezone_NotServerTimezone() {
        LocalDate todayFarEast = LocalDate.now(ZoneOffset.ofHours(14));

        Todo farEastTodo = new Todo();
        farEastTodo.setId(10L);
        farEastTodo.setUser(testUser);
        farEastTodo.setTaskList(testTaskList);
        farEastTodo.setDone(false);
        farEastTodo.setDueDate(todayFarEast);
        farEastTodo.setDueTimezone("+14:00");

        Todo farWestTodo = new Todo();
        farWestTodo.setId(11L);
        farWestTodo.setUser(testUser);
        farWestTodo.setTaskList(testTaskList);
        farWestTodo.setDone(false);
        // Та же самая календарная дата, но в поясе -12:00 она уже наступила позже
        // "сегодняшней" там — задача должна уйти в "upcoming", а не в "today".
        farWestTodo.setDueDate(todayFarEast);
        farWestTodo.setDueTimezone("-12:00");

        // id различает dto друг от друга: у TodoDto лежит Lombok @Data, и два "пустых"
        // TodoDto равны друг другу по equals() — Mockito матчил бы оба стаба toResponse
        // на один и тот же аргумент, и второй тихо перекрыл бы первый.
        TodoDto farEastDto = TodoDto.builder().id(10L).build();
        TodoDto farWestDto = TodoDto.builder().id(11L).build();
        TodoResponse farEastResponse = new TodoResponse();
        farEastResponse.setName("FarEast+14");
        TodoResponse farWestResponse = new TodoResponse();
        farWestResponse.setName("FarWest-12");

        when(todoRepository.findWithDueVisibleToUser(eq(1L), any(LocalDate.class)))
                .thenReturn(Arrays.asList(farEastTodo, farWestTodo));
        when(todoMapper.toDto(farEastTodo)).thenReturn(farEastDto);
        when(todoMapper.toDto(farWestTodo)).thenReturn(farWestDto);
        when(todoMapper.toResponse(farEastDto)).thenReturn(farEastResponse);
        when(todoMapper.toResponse(farWestDto)).thenReturn(farWestResponse);

        DueTodosResponse result = todoService.getDueTodos(1L);

        assertThat(result.getToday()).containsExactly(farEastResponse);
        assertThat(result.getUpcoming()).containsExactly(farWestResponse);
        assertThat(result.getOverdue()).isEmpty();
    }
}
