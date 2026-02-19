package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.account.AccountMemberResponse;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.AccountMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.Account;
import ru.mngerasimenko.todolist.model.AccountRole;
import ru.mngerasimenko.todolist.model.AccountUser;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.AccountRepository;
import ru.mngerasimenko.todolist.repository.AccountUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountUserRepository accountUserRepository;
    private final UserRepository userRepository;
    private final TodoRepository todoRepository;
    private final AccountMapper accountMapper;
    private final TodoMapper todoMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AccountResponse createAccount(String name, String password, Long creatorUserId) {
        if (accountRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Аккаунт с названием '" + name + "' уже существует");
        }

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + creatorUserId));

        Account account = new Account(name, passwordEncoder.encode(password));
        Account savedAccount = accountRepository.save(account);

        // Создатель получает роль ADMIN
        AccountUser accountUser = new AccountUser(savedAccount, creator, AccountRole.ADMIN);
        accountUserRepository.save(accountUser);

        log.info("Создан аккаунт: id={}, name='{}', creatorId={}", savedAccount.getId(), name, creatorUserId);
        return accountMapper.toResponse(savedAccount, AccountRole.ADMIN);
    }

    @Override
    @Transactional
    public AccountResponse joinAccount(String name, String password, Long userId) {
        Account account = accountRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Аккаунт с названием '" + name + "' не найден"));

        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new IllegalArgumentException("Неверный пароль аккаунта");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        // Проверяем — пользователь уже в аккаунте?
        if (accountUserRepository.existsByIdAccountIdAndIdUserId(account.getId(), userId)) {
            // Возвращаем текущую роль
            AccountUser existing = accountUserRepository.findByIdAccountIdAndIdUserId(account.getId(), userId)
                    .orElseThrow();
            log.info("Пользователь уже в аккаунте: accountId={}, userId={}", account.getId(), userId);
            return accountMapper.toResponse(account, existing.getRole());
        }

        AccountUser accountUser = new AccountUser(account, user, AccountRole.USER);
        accountUserRepository.save(accountUser);

        log.info("Пользователь вступил в аккаунт: accountId={}, userId={}", account.getId(), userId);
        return accountMapper.toResponse(account, AccountRole.USER);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByUserId(Long userId) {
        List<AccountUser> accountUsers = accountUserRepository.findByUserId(userId);
        return accountUsers.stream()
                .map(au -> accountMapper.toResponse(au.getAccount(), au.getRole()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountMemberResponse> getMembers(Long accountId, Long requestingUserId) {
        // Проверяем, что запрашивающий является участником аккаунта
        if (!accountUserRepository.existsByIdAccountIdAndIdUserId(accountId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного аккаунта");
        }

        List<AccountUser> members = accountUserRepository.findByIdAccountId(accountId);
        return members.stream()
                .map(accountMapper::toMemberResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByAccount(Long accountId, Long requestingUserId) {
        // Проверяем, что запрашивающий является участником аккаунта
        if (!accountUserRepository.existsByIdAccountIdAndIdUserId(accountId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного аккаунта");
        }

        // Возвращаем публичные + приватные задачи текущего пользователя
        return todoRepository.findByAccountIdVisibleToUser(accountId, requestingUserId).stream()
                .map(todoMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void leaveAccount(Long accountId, Long userId) {
        if (!accountUserRepository.existsByIdAccountIdAndIdUserId(accountId, userId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного аккаунта");
        }

        // Удаляем приватные задачи пользователя в этом аккаунте
        todoRepository.deletePrivateTodosByAccountIdAndUserId(accountId, userId);

        // Удаляем запись участия
        accountUserRepository.deleteByAccountIdAndUserId(accountId, userId);

        log.info("Пользователь вышел из аккаунта: accountId={}, userId={}", accountId, userId);
    }
}
