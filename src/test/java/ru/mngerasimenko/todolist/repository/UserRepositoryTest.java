package ru.mngerasimenko.todolist.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User testUser;

    private final String TEST_EMAIL = "test@mail.ru";
    private final String TEST_PASSWORD = "password123";
    private final String TEST_NAME = "Test User";
    private final String TEST_AUTH_ID = "auth123";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();


        testUser = new User();
        testUser.setAuthId(TEST_AUTH_ID);
        testUser.setName(TEST_NAME);
        testUser.setEmail(TEST_EMAIL);
        testUser.setEmailHash(TEST_EMAIL); // В тестах без шифрования — hash = email
        testUser.setPassword(TEST_PASSWORD);
    }

    @Test
    void save_SavesUserAndGeneratesId() {
        User savedUser = userRepository.save(testUser);

        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getName()).isEqualTo(TEST_NAME);
        assertThat(savedUser.getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void findById_ExistingId_ReturnsUser() {
        User savedUser = userRepository.save(testUser);

        Optional<User> result = userRepository.findById(savedUser.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void findById_NonExistentId_ReturnsEmpty() {
        Optional<User> result = userRepository.findById(999L);

        assertThat(result).isEmpty();
    }

    @Test
    void findAll_ReturnsAllUsers() {
        userRepository.save(testUser);
        User user2 = new User();
        user2.setAuthId("auth-456");
        user2.setName("user2");
        user2.setEmail("user2@mail.ru");
        user2.setPassword("pass2");
        userRepository.save(user2);

        List<User> result = userRepository.findAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(User::getEmail)
                .containsExactlyInAnyOrder(TEST_EMAIL, "user2@mail.ru");
    }

    @Test
    void deleteById_DeletesUser() {
        User savedUser = userRepository.save(testUser);

        userRepository.deleteById(savedUser.getId());
        Optional<User> result = userRepository.findById(savedUser.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void existsById_ExistingId_ReturnsTrue() {
        User savedUser = userRepository.save(testUser);

        boolean exists = userRepository.existsById(savedUser.getId());

        assertThat(exists).isTrue();
    }

    @Test
    void existsById_NonExistentId_ReturnsFalse() {
        boolean exists = userRepository.existsById(999L);

        assertThat(exists).isFalse();
    }

    @Test
    void getUserByEmail_ExistingEmail_ReturnsUser() {
        userRepository.save(testUser);

        User result = userRepository.findByEmailHash(TEST_EMAIL);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(TEST_EMAIL);
        assertThat(result.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    void getUserByEmail_NonExistentEmail_ReturnsNull() {
        User result = userRepository.findByEmailHash("nonexistent@mail.ru");

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmail_WithNullEmail_ReturnsNull() {
        User result = userRepository.findByEmailHash(null);

        assertThat(result).isNull();
    }

    @Test
    void getUserById_ExistingId_ReturnsUser() {
        User savedUser = userRepository.save(testUser);

        User result = userRepository.getUserById(savedUser.getId());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(savedUser.getId());
        assertThat(result.getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void getUserById_NonExistentId_ReturnsNull() {
        User result = userRepository.getUserById(999L);

        assertThat(result).isNull();
    }

    // getUserByName тесты удалены — имя зашифровано, поиск по имени не поддерживается

    @Test
    void getUserByAuthId_ExistingAuthId_ReturnsUser() {
        userRepository.save(testUser);

        User result = userRepository.getUserByAuthId(TEST_AUTH_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAuthId()).isEqualTo(TEST_AUTH_ID);
        assertThat(result.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    void getUserByAuthId_NonExistentAuthId_ReturnsNull() {
        User result = userRepository.getUserByAuthId("nonexistent-auth-id");

        assertThat(result).isNull();
    }

    @Test
    void save_DuplicateEmailHash_ThrowsException() {
        userRepository.save(testUser);

        User duplicateUser = new User();
        duplicateUser.setAuthId("auth-456");
        duplicateUser.setName("duplicate");
        duplicateUser.setEmail("other@mail.ru");
        duplicateUser.setEmailHash(TEST_EMAIL); // Тот же hash — нарушение unique constraint
        duplicateUser.setPassword("duppass");

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void getUserByEmail_WithEmptyString_ReturnsNull() {
        User result = userRepository.findByEmailHash("");

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmail_WithBlankString_ReturnsNull() {
        User result = userRepository.findByEmailHash("   ");

        assertThat(result).isNull();
    }

    // getUserByName_WithSpecialCharacters удалён — имя зашифровано, поиск по имени не поддерживается

    // ===== updateLastActiveAt =====

    @Test
    void updateLastActiveAt_UpdatesTimestamp() {
        // Сохраняем пользователя без lastActiveAt
        User savedUser = userRepository.saveAndFlush(testUser);
        assertThat(savedUser.getLastActiveAt()).isNull();

        LocalDateTime now = LocalDateTime.now();
        userRepository.updateLastActiveAt(savedUser.getId(), now);

        // Flush + clear чтобы @Modifying запрос применился и кеш первого уровня сбросился
        entityManager.flush();
        entityManager.clear();

        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertThat(updatedUser.getLastActiveAt()).isEqualToIgnoringNanos(now);
    }

    // ===== findInactiveUsersForReminder =====

    @Test
    void findInactiveUsersForReminder_FindsInactiveUsers() {
        testUser.setLastActiveAt(LocalDateTime.now().minusDays(5));
        testUser.setReminderCount(0);
        userRepository.saveAndFlush(testUser);

        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(3);

        List<User> result = userRepository.findInactiveUsersForReminder(inactiveSince, 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void findInactiveUsersForReminder_ExcludesRecentlyActiveUsers() {
        testUser.setLastActiveAt(LocalDateTime.now().minusDays(1));
        testUser.setReminderCount(0);
        userRepository.saveAndFlush(testUser);

        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(3);

        List<User> result = userRepository.findInactiveUsersForReminder(inactiveSince, 3);

        assertThat(result).isEmpty();
    }

    @Test
    void findInactiveUsersForReminder_ExcludesMaxRemindedUsers() {
        // Пользователь неактивен, но уже получил 3 напоминания — исключён
        testUser.setLastActiveAt(LocalDateTime.now().minusDays(5));
        testUser.setReminderCount(3);
        userRepository.saveAndFlush(testUser);

        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(3);

        List<User> result = userRepository.findInactiveUsersForReminder(inactiveSince, 3);

        assertThat(result).isEmpty();
    }

    @Test
    void findInactiveUsersForReminder_ExcludesSystemUser() {
        testUser.setLastActiveAt(LocalDateTime.now().minusDays(5));
        testUser.setReminderCount(0);
        User savedUser = userRepository.saveAndFlush(testUser);

        assertThat(savedUser.getId()).isGreaterThan(0L);

        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(3);

        List<User> result = userRepository.findInactiveUsersForReminder(inactiveSince, 3);

        assertThat(result).allMatch(u -> u.getId() > 0);
        assertThat(result).extracting(User::getEmail).contains(TEST_EMAIL);
    }
}