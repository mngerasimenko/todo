package ru.mngerasimenko.todolist.service;

import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.dto.AuthUserDto;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /**
     * ID системного пользователя «Удалённый пользователь»
     */
    static final Long DELETED_USER_ID = 0L;

    private final UserRepository repository;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final TaskListUserRepository taskListUserRepository;
    private final TaskListRepository taskListRepository;
    private final TodoRepository todoRepository;
    private final ru.mngerasimenko.todolist.crypto.CryptoService cryptoService;
    private final CacheManager cacheManager;

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
        User userToDelete = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
        String emailForEvict = userToDelete.getEmail();

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
        evictUserCache(emailForEvict);
    }

    /**
     * Удаление записи из кэша {@code users-me} по email. Вызывается из void-мутаций,
     * где декларативный {@code @CacheEvict} неудобен (SpEL не может читать состояние до мутации
     * или evict'ить сразу несколько ключей типа old/new email).
     * <p>
     * Evict регистрируется как afterCommit-synchronization, чтобы при rollback
     * транзакции не чистить кэш зря. Если транзакция неактивна — evict сразу.
     */
    private void evictUserCache(String email) {
        if (email == null) return;
        // Ключи в кэшах нормализуются через #email?.toLowerCase() в @Cacheable.
        // Сейчас все вызовы приходят из БД, где email уже lowercase (нормализация
        // в createUser/changeEmail), но нормализуем и здесь — защита на случай,
        // если когда-нибудь evict вызовется с MixedCase-email из user-input/request.
        String normalizedEmail = email.toLowerCase();
        Runnable evict = () -> {
            Cache userMe = cacheManager.getCache(RedisCacheConfig.USERS_ME);
            if (userMe != null) userMe.evict(normalizedEmail);
            Cache userAuth = cacheManager.getCache(RedisCacheConfig.USER_AUTH);
            if (userAuth != null) userAuth.evict(normalizedEmail);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getUserByEmail(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        String hash = cryptoService.blindIndex(email.toLowerCase());
        return mapper.toDto(repository.findByEmailHash(hash));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisCacheConfig.USER_AUTH, key = "#email?.toLowerCase()",
            condition = RedisCacheConfig.CACHE_CONDITION, unless = "#result == null")
    public AuthUserDto getUserByEmailForAuth(String email) {
        // Прямой маппинг (минуя UserMapper/UserDto) — чтобы password не терялся при
        // Jackson-сериализации в Redis (в UserDto он под @JsonIgnore).
        if (StringUtils.isBlank(email)) {
            return null;
        }
        String hash = cryptoService.blindIndex(email.toLowerCase());
        User user = repository.findByEmailHash(hash);
        if (user == null) {
            return null;
        }
        return AuthUserDto.builder()
                .email(user.getEmail())
                .password(user.getPassword())
                .build();
    }

    /**
     * Кэшированный вариант для HTTP-ответа. Password обнуляется перед возвратом/кэшированием —
     * чтобы в Redis не попал BCrypt-hash (отдельное хранилище = расширение security-границы),
     * и чтобы кэш не сломал Spring Security login (которому нужен реальный password).
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisCacheConfig.USERS_ME, key = "#email?.toLowerCase()",
            condition = RedisCacheConfig.CACHE_CONDITION, unless = "#result == null")
    public UserDto getUserDtoForResponse(String email) {
        UserDto dto = getUserByEmail(email);
        if (dto != null) {
            dto.setPassword(null);
        }
        return dto;
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

        // Нормализация email в нижний регистр + blind index
        user.setEmail(user.getEmail().toLowerCase());
        user.setEmailHash(cryptoService.blindIndex(user.getEmail()));

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
                    "Не удалось создать аккаунт. Проверьте введённые данные и попробуйте снова");
        }
    }

    @Override
    @Transactional
    public UserDto updateUser(Long id, UserDto userDto) {
        User existingUser = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        String oldEmail = existingUser.getEmail();

        if (!existingUser.getEmail().equals(userDto.getEmail()) && existsByEmail(userDto.getEmail())) {
            throw new IllegalArgumentException("Email " + userDto.getEmail() + " is already taken");
        }

        existingUser.setEmail(userDto.getEmail());
        // Хэшируем пароль при обновлении
        existingUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
        existingUser.setName(userDto.getName());

        User updatedUser = repository.save(existingUser);
        log.info("Обновлён пользователь: id={}, name='{}'", updatedUser.getId(), updatedUser.getName());

        // Evict кэша по старому email и (если изменился) новому
        evictUserCache(oldEmail);
        if (!oldEmail.equals(updatedUser.getEmail())) {
            evictUserCache(updatedUser.getEmail());
        }

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
    @CacheEvict(value = {RedisCacheConfig.USERS_ME, RedisCacheConfig.USER_AUTH}, key = "#result.email.toLowerCase()")
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
        evictUserCache(user.getEmail());
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
        // Evict не нужен: меняются только поля emailVerificationToken/ExpiresAt,
        // которые не используются в auth-путях и /api/users/me.
    }

    @Override
    @Transactional
    public void initiatePasswordReset(String email) {
        User user = repository.findByEmailHash(cryptoService.blindIndex(email.toLowerCase()));
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
        // Evict не нужен: меняются только поля passwordResetToken/ExpiresAt,
        // которые не используются в auth-путях и /api/users/me.
        // Сам пароль меняется в resetPassword() — там evict есть.
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
        evictUserCache(user.getEmail());
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public void changeEmail(Long userId, String newEmail) {
        User user = repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        String oldEmail = user.getEmail();

        // Проверяем, что новый email не занят другим пользователем
        String normalizedEmail = newEmail.toLowerCase();
        String newEmailHash = cryptoService.blindIndex(normalizedEmail);
        User existingUser = repository.findByEmailHash(newEmailHash);
        if (existingUser != null && !existingUser.getId().equals(userId)) {
            throw new IllegalArgumentException("Email " + newEmail + " уже используется");
        }

        // Обновляем email, blind index и сбрасываем верификацию
        user.setEmail(normalizedEmail);
        user.setEmailHash(newEmailHash);
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
        log.info("Email изменён: userId={}, newEmail={}", userId, maskEmail(newEmail));

        // Evict кэша по старому и новому email
        evictUserCache(oldEmail);
        evictUserCache(normalizedEmail);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return repository.findByEmailHash(cryptoService.blindIndex(email.toLowerCase())) != null;
    }

    @Override
    @Transactional
    public void updateLastActiveAt(Long userId) {
        repository.updateLastActiveAt(userId, LocalDateTime.now());
    }

    /**
     * Нарастающие интервалы напоминаний: 7 дней, +14 дней, +30 дней
     */
    private static final int MAX_REMINDERS = 3;
    private static final int[] REMINDER_INTERVALS_DAYS = {0, 14, 30};

    @Override
    @Transactional(readOnly = true)
    public List<User> findInactiveUsersForReminder(int inactiveDays) {
        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(inactiveDays);
        List<User> candidates = repository.findInactiveUsersForReminder(inactiveSince, MAX_REMINDERS);

        // Фильтруем по нарастающему интервалу
        LocalDateTime now = LocalDateTime.now();
        return candidates.stream()
                .filter(user -> isReadyForNextReminder(user, now))
                .toList();
    }

    /**
     * Проверяет, прошёл ли нужный интервал с последнего напоминания.
     * count=0: первое напоминание — сразу (интервал 0 после определения неактивности)
     * count=1: второе — через 7 дней после первого
     * count=2: третье — через 30 дней после второго
     */
    private boolean isReadyForNextReminder(User user, LocalDateTime now) {
        if (user.getReminderCount() == 0) {
            return true; // Первое напоминание — сразу
        }
        if (user.getLastReminderSentAt() == null) {
            return true;
        }
        int intervalDays = REMINDER_INTERVALS_DAYS[Math.min(user.getReminderCount(), REMINDER_INTERVALS_DAYS.length - 1)];
        return user.getLastReminderSentAt().plusDays(intervalDays).isBefore(now);
    }

    @Override
    @Transactional
    public void markReminderSent(Long userId) {
        User user = repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        user.setLastReminderSentAt(LocalDateTime.now());
        user.setReminderCount(user.getReminderCount() + 1);
    }

    /**
     * SHA-256 хеш строки (делегирует в TokenUtils для переиспользования).
     */
    static String sha256(String input) {
        return ru.mngerasimenko.todolist.util.TokenUtils.sha256(input);
    }
}
