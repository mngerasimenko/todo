package ru.mngerasimenko.todolist.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.mngerasimenko.todolist.dto.TodoResponse;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.dto.account.CreateAccountRequest;
import ru.mngerasimenko.todolist.dto.account.JoinAccountRequest;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.service.AccountService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST контроллер для управления аккаунтами.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final UserService userService;
    private final TodoMapper todoMapper;

    /**
     * Создать новый аккаунт.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        AccountResponse response = accountService.createAccount(request.getName(), request.getPassword(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Вступить в существующий аккаунт по названию и паролю.
     */
    @PostMapping("/join")
    public ResponseEntity<AccountResponse> joinAccount(
            @Valid @RequestBody JoinAccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        AccountResponse response = accountService.joinAccount(request.getName(), request.getPassword(), userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Получить список аккаунтов текущего пользователя.
     */
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getMyAccounts(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<AccountResponse> accounts = accountService.getAccountsByUserId(userId);
        return ResponseEntity.ok(accounts);
    }

    /**
     * Получить список участников аккаунта.
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<List<AccountMemberResponse>> getMembers(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<AccountMemberResponse> members = accountService.getMembers(id, userId);
        return ResponseEntity.ok(members);
    }

    /**
     * Получить задачи аккаунта (с учётом приватности).
     */
    @GetMapping("/{id}/todos")
    public ResponseEntity<List<TodoResponse>> getTodosByAccount(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        List<TodoResponse> todos = accountService.getTodosByAccount(id, userId).stream()
                .map(todoMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(todos);
    }

    /**
     * Выйти из аккаунта. Приватные задачи пользователя в аккаунте удаляются.
     */
    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Void> leaveAccount(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        accountService.leaveAccount(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Получить ID текущего аутентифицированного пользователя по username.
     */
    private Long getUserId(UserDetails userDetails) {
        return userService.getUserByUserName(userDetails.getUsername()).getId();
    }
}
