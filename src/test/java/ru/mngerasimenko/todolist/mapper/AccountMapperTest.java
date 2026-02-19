package ru.mngerasimenko.todolist.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.model.Account;
import ru.mngerasimenko.todolist.model.AccountRole;
import ru.mngerasimenko.todolist.model.AccountUser;
import ru.mngerasimenko.todolist.model.AccountUserId;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMapperTest {

    private AccountMapper accountMapper;
    private Account testAccount;
    private User testUser;
    private AccountUser testAccountUser;

    @BeforeEach
    void setUp() {
        accountMapper = new AccountMapper();

        testAccount = new Account("TestAccount", "$2a$10$hash");
        testAccount.setId(1L);
        testAccount.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));

        testUser = new User();
        testUser.setId(2L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");
        testUser.setPassword("hash");

        testAccountUser = new AccountUser();
        testAccountUser.setId(new AccountUserId(1L, 2L));
        testAccountUser.setAccount(testAccount);
        testAccountUser.setUser(testUser);
        testAccountUser.setRole(AccountRole.USER);
        testAccountUser.setJoinedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
    }

    @Test
    void toResponse_WithValidAccountAndRole_ReturnsAccountResponse() {
        AccountResponse response = accountMapper.toResponse(testAccount, AccountRole.ADMIN);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("TestAccount");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    void toResponse_WithNullAccount_ReturnsNull() {
        AccountResponse response = accountMapper.toResponse(null, AccountRole.USER);

        assertThat(response).isNull();
    }

    @Test
    void toResponse_WithNullRole_ReturnsResponseWithNullRole() {
        AccountResponse response = accountMapper.toResponse(testAccount, null);

        assertThat(response).isNotNull();
        assertThat(response.getRole()).isNull();
    }

    @Test
    void toResponse_WithNullCreatedAt_ReturnsResponseWithNullCreatedAt() {
        testAccount.setCreatedAt(null);

        AccountResponse response = accountMapper.toResponse(testAccount, AccountRole.USER);

        assertThat(response).isNotNull();
        assertThat(response.getCreatedAt()).isNull();
    }

    @Test
    void toMemberResponse_WithValidAccountUser_ReturnsMemberResponse() {
        AccountMemberResponse response = accountMapper.toMemberResponse(testAccountUser);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getUserName()).isEqualTo("testuser");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getJoinedAt()).isNotNull();
    }

    @Test
    void toMemberResponse_WithNullAccountUser_ReturnsNull() {
        AccountMemberResponse response = accountMapper.toMemberResponse(null);

        assertThat(response).isNull();
    }

    @Test
    void toMemberResponse_WithNullUser_ReturnsResponseWithNullUserFields() {
        testAccountUser.setUser(null);

        AccountMemberResponse response = accountMapper.toMemberResponse(testAccountUser);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isNull();
        assertThat(response.getUserName()).isNull();
    }

    @Test
    void toMemberResponse_WithNullJoinedAt_ReturnsResponseWithNullJoinedAt() {
        testAccountUser.setJoinedAt(null);

        AccountMemberResponse response = accountMapper.toMemberResponse(testAccountUser);

        assertThat(response).isNotNull();
        assertThat(response.getJoinedAt()).isNull();
    }
}
