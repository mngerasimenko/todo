package ru.mngerasimenko.todolist.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.User;

@Service
public class UserAuthService {
    private final CookieService cookieService;
    private final UserService userService;

    public UserAuthService(CookieService cookieService, UserService userService) {
        this.cookieService = cookieService;
        this.userService = userService;
    }

    public User getAuthUser(HttpServletRequest request) {
        String authCookie = cookieService.getAuthCookieValue(request);
        if (authCookie == null) {
            return null;
        }
        return userService.getUserByAuthId(authCookie);
    }
}
