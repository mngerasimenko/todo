package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.AuthenticationContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.service.CookieService;
import ru.mngerasimenko.todolist.service.UserService;


@Component
public class SecurityService {
    private final UserService userService;
    private final CookieService cookieService;
    private final AuthenticationContext authenticationContext;


    public SecurityService(UserService userService, CookieService cookieService, AuthenticationContext authenticationContext) {
        this.userService = userService;
        this.cookieService = cookieService;
        this.authenticationContext = authenticationContext;
    }

    public UserDto getAuthenticatedUser() {
        UserDetails userDetails = authenticationContext.getAuthenticatedUser(UserDetails.class).get();
        UserDto userDto = userService.getUserByUserName(userDetails.getUsername());
        cookieService.setCookie(userDto.getAuthId(), 30);

        return userDto;
    }

    public void logout() {
        authenticationContext.logout();
        cookieService.deleteCookie();
    }

}
