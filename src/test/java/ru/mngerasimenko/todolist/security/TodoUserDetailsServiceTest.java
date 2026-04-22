package ru.mngerasimenko.todolist.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link TodoUserDetailsService}.
 *
 * Ключевая проверка: auth-путь должен идти через {@code getUserByEmailForAuth},
 * а не через некэшируемый {@code getUserByEmail}. Если кто-то в будущем по ошибке
 * вернёт сюда {@code getUserByEmail}, тест упадёт и предотвратит регресс
 * производительности (hot-path на каждом авторизованном запросе).
 */
@ExtendWith(MockitoExtension.class)
class TodoUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private TodoUserDetailsService service;

    private UserDto user;

    @BeforeEach
    void setUp() {
        user = UserDto.builder()
                .id(1L)
                .email("user@test.local")
                .password("$2a$10$bcryptHashExample")
                .name("Test")
                .authId("auth-1")
                .build();
    }

    @Test
    void loadUserByUsername_UsesCachedAuthPath() {
        when(userService.getUserByEmailForAuth("user@test.local")).thenReturn(user);

        UserDetails result = service.loadUserByUsername("user@test.local");

        assertThat(result).isNotNull();
        verify(userService).getUserByEmailForAuth("user@test.local");
        // Гарантируем, что не-кэшируемый путь не используется в auth — иначе hot-path снова будет бить БД
        verify(userService, never()).getUserByEmail(anyString());
    }

    @Test
    void loadUserByUsername_ReturnsUserDetailsWithPasswordAndRole() {
        when(userService.getUserByEmailForAuth(anyString())).thenReturn(user);

        UserDetails result = service.loadUserByUsername("user@test.local");

        assertThat(result.getUsername()).isEqualTo("user@test.local");
        assertThat(result.getPassword()).isEqualTo("$2a$10$bcryptHashExample");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void loadUserByUsername_UserNotFound_ThrowsUsernameNotFoundException() {
        when(userService.getUserByEmailForAuth("ghost@test.local")).thenReturn(null);

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@test.local"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost@test.local");
    }
}
