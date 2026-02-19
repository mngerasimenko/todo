package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.AccountMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.Account;
import ru.mngerasimenko.todolist.model.AccountRole;
import ru.mngerasimenko.todolist.model.AccountUser;
import ru.mngerasimenko.todolist.model.AccountUserId;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.AccountRepository;
import ru.mngerasimenko.todolist.repository.AccountUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountUserRepository accountUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private TodoMapper todoMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AccountServiceImpl accountService;

    private User testUser;
    private Account testAccount;
    private AccountUser testAccountUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");
        testUser.setPassword("$2a$10$hash");

        testAccount = new Account("TestAccount", "$2a$10$hashedAccountPass");
        testAccount.setId(10L);

        testAccountUser = new AccountUser();
        testAccountUser.setId(new AccountUserId(10L, 1L));
        testAccountUser.setAccount(testAccount);
        testAccountUser.setUser(testUser);
        testAccountUser.setRole(AccountRole.ADMIN);
    }

    // --- createAccount ---

    @Test
    void createAccount_WithValidData_ReturnsAccountResponse() {
        AccountResponse expectedResponse = AccountResponse.builder()
                .id(10L).name("TestAccount").role("ADMIN").build();

        when(accountRepository.findByName("TestAccount")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("pass123")).thenReturn("$2a$10$encodedPass");
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);
        when(accountUserRepository.save(any(AccountUser.class))).thenReturn(testAccountUser);
        when(accountMapper.toResponse(testAccount, AccountRole.ADMIN)).thenReturn(expectedResponse);

        AccountResponse result = accountService.createAccount("TestAccount", "pass123", 1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(accountRepository).save(any(Account.class));
        verify(accountUserRepository).save(any(AccountUser.class));
    }

    @Test
    void createAccount_WithDuplicateName_ThrowsIllegalArgumentException() {
        when(accountRepository.findByName("TestAccount")).thenReturn(Optional.of(testAccount));

        assertThatThrownBy(() -> accountService.createAccount("TestAccount", "pass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestAccount");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createAccount_WithNonExistentUser_ThrowsUserNotFoundException() {
        when(accountRepository.findByName("NewAccount")).thenReturn(Optional.empty());
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.createAccount("NewAccount", "pass", 999L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(accountRepository, never()).save(any());
    }

    // --- joinAccount ---

    @Test
    void joinAccount_WithValidCredentials_ReturnsAccountResponse() {
        AccountResponse expectedResponse = AccountResponse.builder()
                .id(10L).name("TestAccount").role("USER").build();

        when(accountRepository.findByName("TestAccount")).thenReturn(Optional.of(testAccount));
        when(passwordEncoder.matches("pass123", "$2a$10$hashedAccountPass")).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(testUser));
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 2L)).thenReturn(false);
        when(accountUserRepository.save(any(AccountUser.class))).thenReturn(testAccountUser);
        when(accountMapper.toResponse(testAccount, AccountRole.USER)).thenReturn(expectedResponse);

        AccountResponse result = accountService.joinAccount("TestAccount", "pass123", 2L);

        assertThat(result).isNotNull();
        assertThat(result.getRole()).isEqualTo("USER");
        verify(accountUserRepository).save(any(AccountUser.class));
    }

    @Test
    void joinAccount_WhenAlreadyMember_ReturnsExistingRole() {
        AccountUser existingMembership = new AccountUser();
        existingMembership.setRole(AccountRole.ADMIN);

        AccountResponse expectedResponse = AccountResponse.builder()
                .id(10L).name("TestAccount").role("ADMIN").build();

        when(accountRepository.findByName("TestAccount")).thenReturn(Optional.of(testAccount));
        when(passwordEncoder.matches("pass123", "$2a$10$hashedAccountPass")).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 1L)).thenReturn(true);
        when(accountUserRepository.findByIdAccountIdAndIdUserId(10L, 1L))
                .thenReturn(Optional.of(existingMembership));
        when(accountMapper.toResponse(testAccount, AccountRole.ADMIN)).thenReturn(expectedResponse);

        AccountResponse result = accountService.joinAccount("TestAccount", "pass123", 1L);

        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(accountUserRepository, never()).save(any());
    }

    @Test
    void joinAccount_WithWrongPassword_ThrowsIllegalArgumentException() {
        when(accountRepository.findByName("TestAccount")).thenReturn(Optional.of(testAccount));
        when(passwordEncoder.matches("wrongpass", "$2a$10$hashedAccountPass")).thenReturn(false);

        assertThatThrownBy(() -> accountService.joinAccount("TestAccount", "wrongpass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пароль");
    }

    @Test
    void joinAccount_WithNonExistentAccount_ThrowsIllegalArgumentException() {
        when(accountRepository.findByName("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.joinAccount("Unknown", "pass", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    // --- getAccountsByUserId ---

    @Test
    void getAccountsByUserId_ReturnsListOfAccounts() {
        AccountResponse response = AccountResponse.builder()
                .id(10L).name("TestAccount").role("ADMIN").build();

        when(accountUserRepository.findByUserId(1L)).thenReturn(List.of(testAccountUser));
        when(accountMapper.toResponse(testAccount, AccountRole.ADMIN)).thenReturn(response);

        List<AccountResponse> result = accountService.getAccountsByUserId(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("TestAccount");
    }

    @Test
    void getAccountsByUserId_WithNoAccounts_ReturnsEmptyList() {
        when(accountUserRepository.findByUserId(1L)).thenReturn(List.of());

        List<AccountResponse> result = accountService.getAccountsByUserId(1L);

        assertThat(result).isEmpty();
    }

    // --- getMembers ---

    @Test
    void getMembers_WhenUserIsMember_ReturnsMemberList() {
        AccountMemberResponse memberResponse = AccountMemberResponse.builder()
                .userId(1L).userName("testuser").role("ADMIN").build();

        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 1L)).thenReturn(true);
        when(accountUserRepository.findByIdAccountId(10L)).thenReturn(List.of(testAccountUser));
        when(accountMapper.toMemberResponse(testAccountUser)).thenReturn(memberResponse);

        List<AccountMemberResponse> result = accountService.getMembers(10L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserName()).isEqualTo("testuser");
    }

    @Test
    void getMembers_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> accountService.getMembers(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");
    }

    // --- getTodosByAccount ---

    @Test
    void getTodosByAccount_WhenUserIsMember_ReturnsTodos() {
        Todo todo = new Todo();
        todo.setId(1L);
        todo.setName("Task");

        TodoDto todoDto = new TodoDto();
        todoDto.setId(1L);
        todoDto.setName("Task");

        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 1L)).thenReturn(true);
        when(todoRepository.findByAccountIdVisibleToUser(10L, 1L)).thenReturn(List.of(todo));
        when(todoMapper.toDto(todo)).thenReturn(todoDto);

        List<TodoDto> result = accountService.getTodosByAccount(10L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Task");
    }

    @Test
    void getTodosByAccount_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> accountService.getTodosByAccount(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");
    }

    // --- leaveAccount ---

    @Test
    void leaveAccount_WhenUserIsMember_DeletesPrivateTodosAndRemovesMembership() {
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 1L)).thenReturn(true);
        doNothing().when(todoRepository).deletePrivateTodosByAccountIdAndUserId(10L, 1L);
        doNothing().when(accountUserRepository).deleteByAccountIdAndUserId(10L, 1L);

        accountService.leaveAccount(10L, 1L);

        verify(todoRepository).deletePrivateTodosByAccountIdAndUserId(10L, 1L);
        verify(accountUserRepository).deleteByAccountIdAndUserId(10L, 1L);
    }

    @Test
    void leaveAccount_WhenUserIsNotMember_ThrowsIllegalArgumentException() {
        when(accountUserRepository.existsByIdAccountIdAndIdUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> accountService.leaveAccount(10L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("участником");

        verify(accountUserRepository, never()).deleteByAccountIdAndUserId(anyLong(), anyLong());
    }
}
