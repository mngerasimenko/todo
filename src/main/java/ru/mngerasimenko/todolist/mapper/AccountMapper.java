package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.model.Account;
import ru.mngerasimenko.todolist.model.AccountRole;
import ru.mngerasimenko.todolist.model.AccountUser;

@Component
public class AccountMapper {

    /**
     * Конвертация аккаунта в ответ с ролью текущего пользователя.
     */
    public AccountResponse toResponse(Account account, AccountRole role) {
        if (account == null) {
            return null;
        }
        return AccountResponse.builder()
                .id(account.getId())
                .name(account.getName())
                .role(role != null ? role.name() : null)
                .createdAt(account.getCreatedAt() != null ? account.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * Конвертация участника аккаунта в ответ.
     */
    public AccountMemberResponse toMemberResponse(AccountUser accountUser) {
        if (accountUser == null) {
            return null;
        }
        return AccountMemberResponse.builder()
                .userId(accountUser.getUser() != null ? accountUser.getUser().getId() : null)
                .userName(accountUser.getUser() != null ? accountUser.getUser().getName() : null)
                .role(accountUser.getRole() != null ? accountUser.getRole().name() : null)
                .joinedAt(accountUser.getJoinedAt() != null ? accountUser.getJoinedAt().toString() : null)
                .build();
    }
}
