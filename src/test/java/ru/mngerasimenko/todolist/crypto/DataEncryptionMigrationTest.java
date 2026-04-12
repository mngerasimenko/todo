package ru.mngerasimenko.todolist.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест DataEncryptionMigration с H2.
 * Проверяет заполнение email_hash для существующих пользователей.
 */
@JdbcTest
@ActiveProfiles("test")
class DataEncryptionMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes()
    );

    private CryptoService cryptoService;
    private DataEncryptionMigration migration;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService(TEST_KEY);
        migration = new DataEncryptionMigration(jdbcTemplate, cryptoService);

        // Создаём таблицу todo_users с минимальным набором полей
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS todo_users (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                auth_id VARCHAR(128) NOT NULL UNIQUE,
                email TEXT NOT NULL,
                email_hash VARCHAR(64),
                password VARCHAR(128) NOT NULL,
                name TEXT NOT NULL,
                email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                subscription_type VARCHAR(20) NOT NULL DEFAULT 'FREE',
                is_beta_tester BOOLEAN NOT NULL DEFAULT FALSE,
                reminder_count INT NOT NULL DEFAULT 0,
                version BIGINT
            )
        """);

        jdbcTemplate.execute("DELETE FROM todo_users");
    }

    @Test
    void migrateEmailHashes_FillsHashForUsersWithoutHash() {
        // Вставляем пользователя без email_hash
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, password, name) VALUES (?, ?, ?, ?)",
                "auth1", "user@mail.ru", "pass", "User"
        );

        migration.migrateEmailHashes();

        String hash = jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'auth1'", String.class
        );
        assertThat(hash).isNotNull();
        assertThat(hash).isEqualTo(cryptoService.blindIndex("user@mail.ru"));
    }

    @Test
    void migrateEmailHashes_SkipsUsersWithExistingHash() {
        // Пользователь уже имеет email_hash
        String existingHash = "existing_hash_value";
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, email_hash, password, name) VALUES (?, ?, ?, ?, ?)",
                "auth2", "user2@mail.ru", existingHash, "pass", "User2"
        );

        migration.migrateEmailHashes();

        String hash = jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'auth2'", String.class
        );
        // Hash не перезаписан
        assertThat(hash).isEqualTo(existingHash);
    }

    @Test
    void migrateEmailHashes_SkipsEncryptedEmail() {
        // Email выглядит как зашифрованный (Base64, нет @)
        String encryptedEmail = cryptoService.encrypt("real@mail.ru");
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, password, name) VALUES (?, ?, ?, ?)",
                "auth3", encryptedEmail, "pass", "User3"
        );

        migration.migrateEmailHashes();

        String hash = jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'auth3'", String.class
        );
        // Hash не заполнен — email не похож на plain text
        assertThat(hash).isNull();
    }

    @Test
    void migrateEmailHashes_MultipleUsers() {
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, password, name) VALUES (?, ?, ?, ?)",
                "a1", "one@mail.ru", "p", "U1"
        );
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, password, name) VALUES (?, ?, ?, ?)",
                "a2", "two@mail.ru", "p", "U2"
        );
        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, email_hash, password, name) VALUES (?, ?, ?, ?, ?)",
                "a3", "three@mail.ru", "already_set", "p", "U3"
        );

        migration.migrateEmailHashes();

        // Первые два получили hash, третий — нет
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'a1'", String.class
        )).isEqualTo(cryptoService.blindIndex("one@mail.ru"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'a2'", String.class
        )).isEqualTo(cryptoService.blindIndex("two@mail.ru"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'a3'", String.class
        )).isEqualTo("already_set");
    }

    @Test
    void migrateEmailHashes_DisabledEncryption_SkipsMigration() {
        CryptoService disabled = new CryptoService("");
        DataEncryptionMigration disabledMigration = new DataEncryptionMigration(jdbcTemplate, disabled);

        jdbcTemplate.update(
                "INSERT INTO todo_users (auth_id, email, password, name) VALUES (?, ?, ?, ?)",
                "auth4", "user4@mail.ru", "pass", "User4"
        );

        disabledMigration.migrateEmailHashes();

        // Шифрование отключено — hash не заполнен
        String hash = jdbcTemplate.queryForObject(
                "SELECT email_hash FROM todo_users WHERE auth_id = 'auth4'", String.class
        );
        assertThat(hash).isNull();
    }
}
