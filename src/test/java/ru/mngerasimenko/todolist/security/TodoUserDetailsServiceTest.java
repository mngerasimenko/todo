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
import ru.mngerasimenko.todolist.dto.AuthUserDto;
import ru.mngerasimenko.todolist.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link TodoUserDetailsService}.
 *
 * Ключевая проверка: auth-путь должен идти через {@code getUserByEmailForAuth}
 * с возвратом {@link AuthUserDto} (а не {@code UserDto}, у которого password
 * теряется при Jackson-сериализации в Redis из-за {@code @JsonIgnore}).
 */
@ExtendWith(MockitoExtension.class)
class TodoUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private TodoUserDetailsService service;

    private AuthUserDto authUser;

    @BeforeEach
    void setUp() {
        authUser = AuthUserDto.builder()
                .email("user@test.local")
                .password("$2a$10$bcryptHashExample")
                .build();
    }

    @Test
    void loadUserByUsername_UsesCachedAuthPath() {
        when(userService.getUserByEmailForAuth("user@test.local")).thenReturn(authUser);

        UserDetails result = service.loadUserByUsername("user@test.local");

        assertThat(result).isNotNull();
        verify(userService).getUserByEmailForAuth("user@test.local");
    }

    @Test
    void loadUserByUsername_ReturnsUserDetailsWithPasswordAndRole() {
        when(userService.getUserByEmailForAuth(anyString())).thenReturn(authUser);

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
