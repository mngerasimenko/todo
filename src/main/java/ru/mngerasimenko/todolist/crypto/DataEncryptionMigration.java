package ru.mngerasimenko.todolist.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Однократная миграция данных при включении шифрования:
 * 1. Заполняет email_hash (blind index) для существующих пользователей
 * 2. Шифрует plain text email, name пользователей, названия задач и списков
 *
 * Использует флаг '017-data-encrypted' в databasechangelog чтобы не выполняться повторно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataEncryptionMigration {

    private static final String MIGRATION_FLAG = "017-data-encrypted";

    private final JdbcTemplate jdbcTemplate;
    private final CryptoService cryptoService;

    /** Заполняет email_hash для пользователей без него */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    @Order(1)
    public void migrateEmailHashes() {
        if (!cryptoService.isEnabled()) {
            log.info("[encryption-migration] Шифрование отключено, миграция пропущена");
            return;
        }

        List<Map<String, Object>> usersWithoutHash = jdbcTemplate.queryForList(
                "SELECT id, email FROM todo_users WHERE email_hash IS NULL"
        );

        if (usersWithoutHash.isEmpty()) {
            log.info("[encryption-migration] Все email_hash заполнены, миграция не нужна");
            return;
        }

        log.info("[encryption-migration] Найдено {} пользователей без email_hash, заполняем...",
                usersWithoutHash.size());

        int updated = 0;
        for (Map<String, Object> row : usersWithoutHash) {
            Long id = ((Number) row.get("id")).longValue();
            String email = (String) row.get("email");

            if (email != null && looksLikePlainEmail(email)) {
                String hash = cryptoService.blindIndex(email.toLowerCase());
                jdbcTemplate.update("UPDATE todo_users SET email_hash = ? WHERE id = ?", hash, id);
                updated++;
            } else if (email != null) {
                log.warn("[encryption-migration] Пропускаем userId={}: email не похож на plain text", id);
            }
        }

        log.info("[encryption-migration] Заполнено email_hash для {} пользователей", updated);
    }

    /** Шифрует существующие plain text данные. Выполняется однократно (флаг в databasechangelog). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    @Order(2)
    public void encryptPlainTextFields() {
        if (!cryptoService.isEnabled()) return;

        // Проверяем флаг — миграция уже выполнена?
        if (isMigrationDone()) {
            log.info("[encryption-migration] Данные уже зашифрованы (флаг {})", MIGRATION_FLAG);
            return;
        }

        // Пользователи с plain text email (содержит @)
        List<Map<String, Object>> plainUsers = jdbcTemplate.queryForList(
                "SELECT id, email, name FROM todo_users WHERE email LIKE '%@%'"
        );
        if (!plainUsers.isEmpty()) {
            log.info("[encryption-migration] Шифрование email/name для {} пользователей...", plainUsers.size());
            for (Map<String, Object> row : plainUsers) {
                Long id = ((Number) row.get("id")).longValue();
                String email = (String) row.get("email");
                String name = (String) row.get("name");
                jdbcTemplate.update("UPDATE todo_users SET email = ?, name = ? WHERE id = ?",
                        cryptoService.encrypt(email), name != null ? cryptoService.encrypt(name) : null, id);
            }
            log.info("[encryption-migration] Пользователи зашифрованы");
        }

        // Задачи — шифруем все name (однократно)
        int todoCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM todo", Integer.class);
        if (todoCount > 0) {
            List<Map<String, Object>> allTodos = jdbcTemplate.queryForList("SELECT id, name FROM todo");
            log.info("[encryption-migration] Шифрование name для {} задач...", allTodos.size());
            for (Map<String, Object> row : allTodos) {
                Long id = ((Number) row.get("id")).longValue();
                String name = (String) row.get("name");
                if (name != null) {
                    jdbcTemplate.update("UPDATE todo SET name = ? WHERE id = ?",
                            cryptoService.encrypt(name), id);
                }
            }
            log.info("[encryption-migration] Задачи зашифрованы");
        }

        // Списки — шифруем все name (однократно)
        int listCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_list", Integer.class);
        if (listCount > 0) {
            List<Map<String, Object>> allLists = jdbcTemplate.queryForList("SELECT id, name FROM task_list");
            log.info("[encryption-migration] Шифрование name для {} списков...", allLists.size());
            for (Map<String, Object> row : allLists) {
                Long id = ((Number) row.get("id")).longValue();
                String name = (String) row.get("name");
                if (name != null) {
                    jdbcTemplate.update("UPDATE task_list SET name = ? WHERE id = ?",
                            cryptoService.encrypt(name), id);
                }
            }
            log.info("[encryption-migration] Списки зашифрованы");
        }

        // Ставим флаг — миграция выполнена
        setMigrationDone();
        log.info("[encryption-migration] Миграция данных завершена, флаг установлен");
    }

    private boolean isMigrationDone() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM databasechangelog WHERE id = ?", Integer.class, MIGRATION_FLAG);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void setMigrationDone() {
        jdbcTemplate.update(
                "INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype) " +
                "VALUES (?, 'mngerasimenko', 'DataEncryptionMigration.java', NOW(), " +
                "(SELECT COALESCE(MAX(orderexecuted), 0) + 1 FROM databasechangelog), 'EXECUTED')",
                MIGRATION_FLAG
        );
    }

    /** Проверяет что строка похожа на plain text email */
    private boolean looksLikePlainEmail(String email) {
        return email.contains("@") && !email.contains("==");
    }
}
