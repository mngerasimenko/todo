package ru.mngerasimenko.todolist.service;

import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** ID системного пользователя «Удалённый пользователь» */
    static final Long DELETED_USER_ID = 0L;

    private final UserRepository repository;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final TaskListUserRepository taskListUserRepository;
    private final TaskListRepository taskListRepository;
    private final TodoRepository todoRepository;

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getAll() {
        return repository.findAll().stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new UserNotFoundException("User not found with id: " + id);
        }

        // Обрабатываем каждый список, в котором состоит пользователь
        List<TaskListUser> memberships = taskListUserRepository.findByUserId(id);
        for (TaskListUser membership : memberships) {
            Long listId = membership.getTaskList().getId();
            List<TaskListUser> allMembers = taskListUserRepository.findByIdListId(listId);

            if (allMembers.size() == 1) {
                // Единственный участник — удаляем список целиком
                todoRepository.deleteByListId(listId);
                taskListUserRepository.deleteByListId(listId);
                taskListRepository.deleteByListId(listId);
            } else {
                // Есть другие участники
                if (membership.getRole() == TaskListRole.ADMIN) {
                    // Передаём ADMIN первому другому участнику и сохраняем до JPQL-запросов
                    allMembers.stream()
                            .filter(m -> !m.getUser().getId().equals(id))
                            .findFirst()
                            .ifPresent(m -> {
                                m.setRole(TaskListRole.ADMIN);
                                taskListUserRepository.saveAndFlush(m);
                            });
                }
                // Удаляем приватные задачи пользователя в этом списке
                todoRepository.deletePrivateTodosByListIdAndUserId(listId, id);
                // Удаляем связь пользователь-список
                taskListUserRepository.deleteByListIdAndUserId(listId, id);
            }
        }

        // Переносим публичные задачи пользователя на системного «Удалённый пользователь»
        User deletedUser = repository.findById(DELETED_USER_ID)
                .orElseThrow(() -> new IllegalStateException("Системный пользователь (id=0) не найден"));
        todoRepository.reassignUser(id, deletedUser.getId());

        // Переносим completor_user на системного пользователя
        todoRepository.reassignCompletorUser(id, deletedUser.getId());

        // Удаляем пользователя
        repository.deleteById(id);
        log.info("Удалён пользователь: id={}", id);
    }

    @Override
    public UserDto getUserByUserName(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        return mapper.toDto(repository.getUserByName(userName));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getUserByEmail(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        return mapper.toDto(repository.getUserByEmail(email.toLowerCase()));
    }

    @Override
    public UserDto getUserByAuthId(String authId) {
        if (StringUtils.isBlank(authId)) {
            return null;
        }
        return mapper.toDto(repository.getUserByAuthId(authId));
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public UserDto createUser(UserDto userDto) {
        User user = mapper.toEntity(userDto);

        // Нормализация email в нижний регистр
        user.setEmail(user.getEmail().toLowerCase());

        if (user.getAuthId() == null) {
            UUID uuid = UUID.randomUUID();
            user.setAuthId(uuid.toString());
        }

        // Хэшируем пароль перед сохранением
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // Генерация токена верификации email
        String rawToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(sha256(rawToken));
        user.setEmailVerificationExpiresAt(
                LocalDateTime.now().plusHours(emailProperties.getVerificationTokenTtlHours()));
        user.setEmailVerified(false);

        // Атомарная вставка: нет TOCTOU — уникальность гарантирует БД (UNIQUE на name, email)
        try {
            User savedUser = repository.saveAndFlush(user);
            log.info("Создан пользователь: id={}, name='{}'", savedUser.getId(), savedUser.getName());

            // Отправка письма верификации (асинхронно)
            emailService.sendVerificationEmail(savedUser.getEmail(), rawToken);

            return mapper.toDto(savedUser);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "Пользователь с таким именем или email уже существует");
        }
    }

    @Override
    @Transactional
    public UserDto updateUser(Long id, UserDto userDto) {
        User existingUser = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        if (!existingUser.getEmail().equals(userDto.getEmail()) && existsByEmail(userDto.getEmail())) {
            throw new IllegalArgumentException("Email " + userDto.getEmail() + " is already taken");
        }

        existingUser.setEmail(userDto.getEmail());
        // Хэшируем пароль при обновлении
        existingUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
        existingUser.setName(userDto.getName());

        User updatedUser = repository.save(existingUser);
        log.info("Обновлён пользователь: id={}, name='{}'", updatedUser.getId(), updatedUser.getName());
        return mapper.toDto(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getUserById(Long id) {
        User user = repository.getUserById(id);
        if (user == null) {
            throw new UserNotFoundException("User not found with id: " + id);
        }
        return mapper.toDto(user);
    }

    @Override
    @Transactional
    public UserDto updateColors(Long id, String createdTaskColor, String completedTaskColor) {
        User user = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
        user.setCreatedTaskColor(createdTaskColor);
        user.setCompletedTaskColor(completedTaskColor);
        User savedUser = repository.save(user);
        log.info("Обновлены цвета пользователя: id={}", id);
        return mapper.toDto(savedUser);
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        String tokenHash = sha256(token);
        User user = repository.findByEmailVerificationToken(tokenHash);
        if (user == null) {
            throw new TokenExpiredException("Невалидный токен верификации");
        }
        if (user.getEmailVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            throw new TokenExpiredException("Токен верификации истёк");
        }
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiresAt(null);
        repository.save(user);
        log.info("Email подтверждён: userId={}", user.getId());
    }

    @Override
    @Transactional
    public void resendVerificationEmail(Long userId) {
        User user = repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("Email уже подтверждён");
        }
        String rawToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(sha256(rawToken));
        user.setEmailVerificationExpiresAt(
                LocalDateTime.now().plusHours(emailProperties.getVerificationTokenTtlHours()));
        repository.save(user);
        emailService.sendVerificationEmail(user.getEmail(), rawToken);
        log.info("Повторное письмо верификации отправлено: userId={}", userId);
    }

    @Override
    @Transactional
    public void initiatePasswordReset(String email) {
        User user = repository.getUserByEmail(email.toLowerCase());
        // Всегда 200 — не раскрываем существование аккаунта
        if (user == null || !user.isEmailVerified()) {
            return;
        }
        String rawToken = UUID.randomUUID().toString();
        user.setPasswordResetToken(sha256(rawToken));
        user.setPasswordResetExpiresAt(
                LocalDateTime.now().plusHours(emailProperties.getResetTokenTtlHours()));
        repository.save(user);
        emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
        log.info("Письмо сброса пароля отправлено: userId={}", user.getId());
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        String tokenHash = sha256(token);
        User user = repository.findByPasswordResetToken(tokenHash);
        if (user == null) {
            throw new TokenExpiredException("Невалидный токен сброса пароля");
        }
        if (user.getPasswordResetExpiresAt().isBefore(LocalDateTime.now())) {
            throw new TokenExpiredException("Токен сброса пароля истёк");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        repository.save(user);
        log.info("Пароль сброшен: userId={}", user.getId());
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public void changeEmail(Long userId, String newEmail) {
        User user = repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        // Проверяем, что новый email не занят другим пользователем
        String normalizedEmail = newEmail.toLowerCase();
        User existingUser = repository.getUserByEmail(normalizedEmail);
        if (existingUser != null && !existingUser.getId().equals(userId)) {
            throw new IllegalArgumentException("Email " + newEmail + " уже используется");
        }

        // Обновляем email и сбрасываем верификацию
        user.setEmail(normalizedEmail);
        user.setEmailVerified(false);

        // Генерируем новый токен верификации
        String rawToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(sha256(rawToken));
        user.setEmailVerificationExpiresAt(
                LocalDateTime.now().plusHours(emailProperties.getVerificationTokenTtlHours()));

        try {
            repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Email " + newEmail + " уже используется");
        }

        // Отправляем письмо верификации на новый email
        emailService.sendVerificationEmail(newEmail, rawToken);
        log.info("Email изменён: userId={}, newEmail={}", userId, newEmail);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return repository.getUserByEmail(email.toLowerCase()) != null;
    }

    @Transactional(readOnly = true)
    public boolean existsByUserName(String userName) {
        return repository.getUserByName(userName) != null;
    }

    /**
     * SHA-256 хеш строки (делегирует в TokenUtils для переиспользования).
     */
    static String sha256(String input) {
        return ru.mngerasimenko.todolist.util.TokenUtils.sha256(input);
    }
}
