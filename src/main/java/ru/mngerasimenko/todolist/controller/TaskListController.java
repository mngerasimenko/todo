package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.dto.list.CreateListRequest;
import ru.mngerasimenko.todolist.dto.list.JoinListRequest;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.TaskListService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST контроллер для управления списками задач.
 */
@RestController
@RequestMapping("/api/lists")
@RequiredArgsConstructor
public class TaskListController {

    private final TaskListService taskListService;
    private final UserService userService;
    private final TodoMapper todoMapper;

    /**
     * Создать новый список задач.
     */
    @PostMapping
    public ResponseEntity<ListResponse> createList(
            @Valid @RequestBody CreateListRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        ListResponse response = taskListService.createList(request.getName(), request.getPassword(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Вступить в существующий список по названию и паролю.
     */
    @PostMapping("/join")
    public ResponseEntity<ListResponse> joinList(
            @Valid @RequestBody JoinListRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        ListResponse response = taskListService.joinList(request.getName(), request.getPassword(), userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Получить списки задач текущего пользователя.
     */
    @GetMapping
    public ResponseEntity<List<ListResponse>> getMyLists(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<ListResponse> lists = taskListService.getListsByUserId(userId);
        return ResponseEntity.ok(lists);
    }

    /**
     * Получить список участников списка задач.
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<List<ListMemberResponse>> getMembers(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<ListMemberResponse> members = taskListService.getMembers(id, userId);
        return ResponseEntity.ok(members);
    }

    /**
     * Получить задачи списка (с учётом приватности).
     */
    @GetMapping("/{id}/todos")
    public ResponseEntity<List<TodoResponse>> getTodosByList(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<TodoResponse> todos = taskListService.getTodosByList(id, userId).stream()
                .map(todoMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(todos);
    }

    /**
     * Выйти из списка. Приватные задачи пользователя в списке удаляются.
     */
    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Void> leaveList(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        taskListService.leaveList(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Получить ID текущего аутентифицированного пользователя по username.
     */
    private Long getUserId(UserDetails userDetails) {
        return userService.getUserByUserName(userDetails.getUsername()).getId();
    }
}
