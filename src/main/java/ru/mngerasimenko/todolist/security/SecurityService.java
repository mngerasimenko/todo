package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.AuthenticationContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.CookieService;
import ru.mngerasimenko.todolist.service.UserService;


@Component
public class SecurityService {
    private final UserService userService;
    private final CookieService cookieService;
    private final AuthenticationContext authenticationContext;
    private User authenticatedUser;


    public SecurityService(UserService userService, CookieService cookieService, AuthenticationContext authenticationContext) {
        this.userService = userService;
        this.cookieService = cookieService;
        this.authenticationContext = authenticationContext;
    }

    public User getAuthenticatedUser() {
        if (authenticatedUser != null) {
            return authenticatedUser;
        }

        UserDetails userDetails = authenticationContext.getAuthenticatedUser(UserDetails.class).get();
        User user = userService.getUserByUserName(userDetails.getUsername());
        cookieService.setCookie(user.getAuthId(), 30);

        authenticatedUser = user;
        return user;
    }


    public void logout() {
        authenticatedUser = null;
        authenticationContext.logout();
        cookieService.deleteCookie();
    }

//    public User getUser() {
//        String userName = getAuthenticatedUser().getUsername();
//        User user = userService.getUserByUserName(userName);
//        if (user == null) {
//            throw new NullPointerException("User " + userName + " not found");
//        }
//        return user;
//    }
}
