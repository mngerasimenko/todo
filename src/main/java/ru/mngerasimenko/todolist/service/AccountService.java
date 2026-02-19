package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;

import java.util.List;

public interface AccountService {

    /**
     * Создать новый аккаунт. Создатель автоматически получает роль ADMIN.
     */
    AccountResponse createAccount(String name, String password, Long creatorUserId);

    /**
     * Вступить в существующий аккаунт по названию и паролю.
     */
    AccountResponse joinAccount(String name, String password, Long userId);

    /**
     * Получить список аккаунтов текущего пользователя.
     */
    List<AccountResponse> getAccountsByUserId(Long userId);

    /**
     * Получить список участников аккаунта.
     */
    List<AccountMemberResponse> getMembers(Long accountId, Long requestingUserId);

    /**
     * Получить задачи аккаунта (с учётом приватности).
     */
    List<TodoDto> getTodosByAccount(Long accountId, Long requestingUserId);

    /**
     * Выйти из аккаунта. Приватные задачи пользователя в аккаунте удаляются.
     */
    void leaveAccount(Long accountId, Long userId);
}
