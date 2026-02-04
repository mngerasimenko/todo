package ru.mngerasimenko.todolist.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.CookieService;
import ru.mngerasimenko.todolist.service.UserService;

@Service
public class UserAuthService {
    private final CookieService cookieService;
    private final UserService userService;
    private final UserDetailsService userDetailsService;

    public UserAuthService(CookieService cookieService, UserService userService, UserDetailsService userDetailsService) {
        this.cookieService = cookieService;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
    }

    public User getAuthUser(HttpServletRequest request) {
        String authCookie = cookieService.getAuthCookieValue(request);
        if (authCookie == null) {
            return null;
        }
        return userService.getUserByAuthId(authCookie);
    }

    public UserDetails getUserDetailsByName(String userName) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(userName);
        if (userDetails == null) {
            throw new RuntimeException("Not found user: " + userName);
        }
        return userDetails;
    }

}
