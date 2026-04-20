package ru.mngerasimenko.todolist.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import ru.mngerasimenko.todolist.config.SuperAdminProperties;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuperAdminGuardTest {

    private SuperAdminProperties properties;
    private SuperAdminGuard guard;

    @BeforeEach
    void setUp() {
        properties = new SuperAdminProperties();
        guard = new SuperAdminGuard(properties);
    }

    @Test
    void check_nullAuthentication_ReturnsFalse() {
        assertThat(guard.check(null)).isFalse();
    }

    @Test
    void check_notAuthenticated_ReturnsFalse() {
        properties.setEmails(List.of("admin@example.com"));
        Authentication auth = new UsernamePasswordAuthenticationToken("admin@example.com", "n/a");
        // по умолчанию isAuthenticated()=true у этого конструктора без authorities — обходим через setAuthenticated(false)
        auth.setAuthenticated(false);
        assertThat(guard.check(auth)).isFalse();
    }

    @Test
    void check_emailInWhitelist_ReturnsTrue() {
        properties.setEmails(List.of("admin@example.com"));
        Authentication auth = authOf("admin@example.com");
        assertThat(guard.check(auth)).isTrue();
    }

    @Test
    void check_emailDifferentCase_ReturnsTrue() {
        properties.setEmails(List.of("Admin@Example.com"));
        Authentication auth = authOf("ADMIN@example.COM");
        assertThat(guard.check(auth)).isTrue();
    }

    @Test
    void check_emailNotInWhitelist_ReturnsFalse() {
        properties.setEmails(List.of("admin@example.com"));
        Authentication auth = authOf("user@example.com");
        assertThat(guard.check(auth)).isFalse();
    }

    @Test
    void check_emptyWhitelist_ReturnsFalse() {
        properties.setEmails(Collections.emptyList());
        Authentication auth = authOf("admin@example.com");
        assertThat(guard.check(auth)).isFalse();
    }

    @Test
    void check_whitelistContainsBlankEntries_IgnoresThem() {
        properties.setEmails(List.of("", "   ", "admin@example.com"));
        assertThat(guard.check(authOf("admin@example.com"))).isTrue();
        assertThat(guard.check(authOf("any@example.com"))).isFalse();
    }

    @Test
    void check_principalBlank_ReturnsFalse() {
        properties.setEmails(List.of("admin@example.com"));
        Authentication auth = new UsernamePasswordAuthenticationToken("", "n/a", List.of());
        assertThat(guard.check(auth)).isFalse();
    }

    private Authentication authOf(String principal) {
        return new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
    }
}
