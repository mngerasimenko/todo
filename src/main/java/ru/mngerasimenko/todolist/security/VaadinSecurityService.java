package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.service.CookieService;
import ru.mngerasimenko.todolist.service.UserService;

/**
 * Сервис для работы с аутентификацией в Vaadin UI.
 * Использует Vaadin AuthenticationContext для управления сессиями.
 */
@Component
@RequiredArgsConstructor
public class VaadinSecurityService {

    private final UserService userService;
    private final CookieService cookieService;
    private final AuthenticationContext authenticationContext;

    /**
     * Получает аутентифицированного пользователя из Vaadin AuthenticationContext
     *
     * @return DTO пользователя
     */
    public UserDto getAuthenticatedUser() {
        UserDetails userDetails = authenticationContext.getAuthenticatedUser(UserDetails.class).orElse(null);

        if (userDetails == null) {
            return null;
        }

        UserDto userDto = userService.getUserByUserName(userDetails.getUsername());

        if (userDto != null && userDto.getAuthId() != null) {
            cookieService.setCookie(userDto.getAuthId(), 30);
        }

        return userDto;
    }

    /**
     * Выполняет выход пользователя из системы (для Vaadin UI)
     */
    public void logout() {
        authenticationContext.logout();
        cookieService.deleteCookie();
    }
}
