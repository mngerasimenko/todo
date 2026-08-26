package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.DueTodosResponse;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.TodoRequest;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.TodoService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

/**
 * REST-контроллер для управления задачами.
 * Эндпоинты: создание, обновление, удаление, получение, отметка выполнения.
 */
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
@Slf4j
public class TodoRestController {

    private final TodoService todoService;
    private final TodoMapper todoMapper;
    private final UserService userService;

    /** Создание новой задачи */
    @PostMapping("/create")
    public ResponseEntity<TodoResponse> create(@Valid @RequestBody TodoRequest request,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        TodoDto todoDto = todoMapper.toDto(request);

        // Автор — всегда владелец токена. Раньше user_id брался из тела как есть, и этого
        // хватало для IDOR: проверка членства в списке (TodoServiceImpl) смотрит на автора,
        // то есть на ЖЕРТВУ, а не на отправителя — любой держатель валидного JWT мог писать
        // задачи в чужой список от чужого имени и тратить чужие лимиты подписки.
        // Значение из тела не отвергаем, а игнорируем: выпущенные клиенты шлют там свой же id,
        // и 403 сломал бы их на ровном месте.
        if (request.getUserId() != null && !request.getUserId().equals(currentUser.getId())) {
            log.warn("Попытка создать задачу от чужого имени: user_id из тела={}, автор из токена={}",
                    request.getUserId(), currentUser.getId());
        }
        todoDto.setUserId(currentUser.getId());
        // id из тела на создании не нужен: сейчас его гасит persist по IDENTITY, но это
        // побочный эффект nullable @Version, а не намерение. Раз уж перестали доверять телу — гасим явно.
        todoDto.setId(null);

        TodoDto createdTodo = todoService.createTodo(todoDto);
        TodoResponse response = todoMapper.toResponse(createdTodo);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Обновление задачи по ID */
    @PutMapping("/{id}")
    public ResponseEntity<TodoResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody TodoRequest request,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        TodoDto todoDto = todoMapper.toDto(request);
        TodoDto updatedTodo = todoService.updateTodo(id, todoDto, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(updatedTodo);
        return ResponseEntity.ok(response);
    }

    /** Получение задачи по ID (с проверкой принадлежности к списку) */
    @GetMapping("/{id}")
    public ResponseEntity<TodoResponse> getTodoById(@PathVariable Long id,
                                                     @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        TodoDto todoDto = todoService.getTodoById(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Получение всех задач текущего пользователя */
    @GetMapping("/all")
    public ResponseEntity<List<TodoResponse>> getAllTodos(
            @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        List<TodoDto> todos = todoService.getAllTodos(currentUser.getId());
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Задачи со сроком, сгруппированные для экрана «Сегодня»: просроченные, сегодняшние, ближайшие */
    @GetMapping("/due")
    public ResponseEntity<DueTodosResponse> getDueTodos(
            @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        DueTodosResponse response = todoService.getDueTodos(currentUser.getId());
        return ResponseEntity.ok(response);
    }

    /** Получение задач пользователя по его ID (с проверкой доступа) */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TodoResponse>> getTodosByUserId(@PathVariable Long userId,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        List<TodoDto> todos = todoService.getTodosByUserId(userId, currentUser.getId());
        List<TodoResponse> responses = todos.stream()
                .map(todoMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Отметить задачу как выполненную (исполнитель — текущий пользователь из JWT) */
    @PatchMapping("/{id}/done")
    public ResponseEntity<TodoResponse> markAsDone(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        TodoDto todoDto = todoService.markAsDone(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Снять отметку выполнения задачи */
    @PatchMapping("/{id}/undone")
    public ResponseEntity<TodoResponse> markAsUndone(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        TodoDto todoDto = todoService.markAsUndone(id, currentUser.getId());
        TodoResponse response = todoMapper.toResponse(todoDto);
        return ResponseEntity.ok(response);
    }

    /** Удаление задачи по ID */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        @AuthenticationPrincipal UserDetails userDetails) {
        UserDto currentUser = requireCurrentUser(userDetails);
        todoService.deleteTodo(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Текущий пользователь по токену — с явной ошибкой вместо NPE.
     * {@code getUserDtoForResponse} возвращает null, если строки уже нет (аккаунт удалён,
     * email сменён, а токен ещё жив), и голое {@code currentUser.getId()} давало бы HTTP 500.
     * Берём кэшируемый вариант: лишний поход в БД с расшифровкой на каждый запрос не нужен,
     * а пароль в такой DTO не попадает.
     *
     * <p><b>Именно 401, а не 404.</b> Android трактует 404 на todo-операции как «задача удалена
     * на сервере» и стирает её из Room ({@code SyncManagerImpl.handleError}), причём у
     * несинхронизированного создания {@code entityId} — отрицательный временный id, то есть
     * задача пропадает совсем. Мёртвая сессия не должна выглядеть для клиента как удалённые
     * данные: на 401 очередь операций уцелеет и уйдёт в ретрай, а токен обновится или
     * пользователь честно разлогинится.
     */
    private UserDto requireCurrentUser(UserDetails userDetails) {
        UserDto user = userService.getUserDtoForResponse(userDetails.getUsername());
        if (user == null) {
            throw new UsernameNotFoundException("Authenticated user no longer exists");
        }
        return user;
    }

}
