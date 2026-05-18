package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.list.AcceptInviteRequest;
import ru.mngerasimenko.todolist.dto.list.CreateListRequest;
import ru.mngerasimenko.todolist.dto.list.InviteInfoResponse;
import ru.mngerasimenko.todolist.dto.list.InviteRequest;
import ru.mngerasimenko.todolist.dto.list.InviteResponse;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.dto.list.UpdateListRequest;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.TaskListService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;
import java.util.Map;

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
        ListResponse response = taskListService.createList(request.getName(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
                .toList();
        return ResponseEntity.ok(todos);
    }

    /**
     * Выйти из списка.
     * MEMBER — удаляются приватные задачи. ADMIN — передаются права или удаляется список.
     */
    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Map<String, String>> leaveList(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String message = taskListService.leaveList(id, userId);
        return ResponseEntity.ok(Map.of("message", message));
    }

    /**
     * Удалить список задач. Только администратор списка может выполнить удаление.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteList(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        taskListService.deleteList(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Частично обновить список (PATCH-семантика). Только ADMIN списка.
     * Поля {@code name} и {@code color} опциональные.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ListResponse> updateList(
            @PathVariable Long id,
            @Valid @RequestBody UpdateListRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        ListResponse response = taskListService.updateList(id, userId, request.getName(), request.getColor());
        return ResponseEntity.ok(response);
    }

    /**
     * Создать приглашение в список (только ADMIN).
     * Если в теле указан email — отправляется письмо с приглашением.
     */
    @PostMapping("/{id}/invite")
    public ResponseEntity<InviteResponse> createInvite(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) InviteRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String email = (request != null) ? request.getEmail() : null;
        InviteResponse response = taskListService.createInvite(id, userId, email);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Получить информацию о приглашении по токену (публичный, без JWT).
     */
    @GetMapping("/invite/{token}")
    public ResponseEntity<InviteInfoResponse> getInviteInfo(@PathVariable String token) {
        InviteInfoResponse response = taskListService.getInviteInfo(token);
        return ResponseEntity.ok(response);
    }

    /**
     * Принять приглашение по токену (требует JWT).
     */
    @PostMapping("/invite/accept")
    public ResponseEntity<ListResponse> acceptInvite(
            @Valid @RequestBody AcceptInviteRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        ListResponse response = taskListService.acceptInvite(request.getToken(), userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Получить ID текущего аутентифицированного пользователя по username.
     */
    private Long getUserId(UserDetails userDetails) {
        return userService.getUserByEmail(userDetails.getUsername()).getId();
    }
}
