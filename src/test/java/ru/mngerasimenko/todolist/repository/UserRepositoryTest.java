package ru.mngerasimenko.todolist.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import ru.mngerasimenko.todolist.model.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

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

        User result = userRepository.getUserByEmail(TEST_EMAIL);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(TEST_EMAIL);
        assertThat(result.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    void getUserByEmail_NonExistentEmail_ReturnsNull() {
        User result = userRepository.getUserByEmail("nonexistent@mail.ru");

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmail_WithNullEmail_ReturnsNull() {
        User result = userRepository.getUserByEmail(null);

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmailAndPassword_ExistingCredentials_ReturnsUser() {
        userRepository.save(testUser);

        User result = userRepository.getUserByEmailAndPassword(
                TEST_EMAIL,
                TEST_PASSWORD
        );

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void getUserByEmailAndPassword_WrongPassword_ReturnsNull() {
        userRepository.save(testUser);

        User result = userRepository.getUserByEmailAndPassword(
                TEST_EMAIL,
                "wrongpassword"
        );

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmailAndPassword_WrongEmail_ReturnsNull() {
        userRepository.save(testUser);

        User result = userRepository.getUserByEmailAndPassword(
                "wrong@mail.ru",
                TEST_PASSWORD
        );

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

    @Test
    void getUserByName_ExistingName_ReturnsUser() {
        userRepository.save(testUser);

        User result = userRepository.getUserByName(TEST_NAME);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(TEST_NAME);
        assertThat(result.getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    void getUserByName_NonExistentName_ReturnsNull() {
        User result = userRepository.getUserByName("nonexistent");

        assertThat(result).isNull();
    }

    @Test
    void getUserByName_CaseInsensitiveSearch() {
        userRepository.save(testUser);

        User result1 = userRepository.getUserByName(TEST_NAME); // Заглавные
        User result2 = userRepository.getUserByName(TEST_NAME); // CamelCase

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result1.getEmail()).isEqualTo(TEST_EMAIL);
        assertThat(result2.getEmail()).isEqualTo(TEST_EMAIL);
    }

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
    void save_DuplicateEmail_ThrowsException() {
        userRepository.save(testUser);

        User duplicateUser = new User();
        duplicateUser.setAuthId("auth-456");
        duplicateUser.setName("duplicate");
        duplicateUser.setEmail(TEST_EMAIL); // Та же почта
        duplicateUser.setPassword("duppass");

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("could not execute statement");
    }

    @Test
    void getUserByEmail_WithEmptyString_ReturnsNull() {
        User result = userRepository.getUserByEmail("");

        assertThat(result).isNull();
    }

    @Test
    void getUserByEmail_WithBlankString_ReturnsNull() {
        User result = userRepository.getUserByEmail("   ");

        assertThat(result).isNull();
    }

    @Test
    void getUserByName_WithSpecialCharacters_ReturnsUser() {
        User specialUser = new User();
        specialUser.setAuthId("auth-special");
        specialUser.setName("user@domain.com|token-123"); // Спецсимволы
        specialUser.setEmail("special@mail.ru");
        specialUser.setPassword("specialpass");
        userRepository.save(specialUser);

        User result = userRepository.getUserByName("user@domain.com|token-123");

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("user@domain.com|token-123");
    }
}