package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.mapper.VaadinServiceWrapper;
import ru.mngerasimenko.todolist.service.CookieService;
import ru.mngerasimenko.todolist.service.UserService;

/**
 * Сервис для работы с аутентификацией в Vaadin UI.
 * Использует Vaadin AuthenticationContext для управления сессиями.
 * Содержит Vaadin-специфичную логику работы с cookie через VaadinResponse.
 */
@Component
@RequiredArgsConstructor
public class VaadinSecurityService {

    private final UserService userService;
    private final CookieService cookieService;
    private final AuthenticationContext authenticationContext;
    private final VaadinServiceWrapper vaadinServiceWrapper;

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
            setVaadinCookie(CookieService.COOKIE_NAME, userDto.getAuthId(), 30);
        }

        return userDto;
    }

    /**
     * Выполняет выход пользователя из системы (для Vaadin UI)
     */
    public void logout() {
        authenticationContext.logout();
        setVaadinCookie(CookieService.COOKIE_NAME, "", 0);
    }

    /**
     * Устанавливает cookie через Vaadin response
     */
    private void setVaadinCookie(String name, String value, int maxAgeDay) {
        VaadinResponse response = vaadinServiceWrapper.getCurrentResponse();
        if (response == null) return;

        Cookie cookie = cookieService.createCookie(name, value, maxAgeDay);
        response.addCookie(cookie);
    }
}
