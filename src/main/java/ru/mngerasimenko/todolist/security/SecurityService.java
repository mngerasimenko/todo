package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.servlet.http.Cookie;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.model.Mac2User;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.MacService;
import ru.mngerasimenko.todolist.service.UserService;
import ru.mngerasimenko.todolist.utils.Utils;


@Component
public class SecurityService {
    private UserService userService;
    private MacService macService;
    private final AuthenticationContext authenticationContext;
    private User authenticatedUser;


    public SecurityService(UserService userService, MacService macService, AuthenticationContext authenticationContext) {
        this.userService = userService;
        this.macService = macService;
        this.authenticationContext = authenticationContext;
    }

    public User getAuthenticatedUser() {
        if (authenticatedUser != null) {
            return authenticatedUser;
        }

        User user;
        String macAddress = Utils.getMacAddress();
        user = macService.getUserByMacAddress(macAddress);
        if (user == null) {
            UserDetails userDetails = authenticationContext.getAuthenticatedUser(UserDetails.class).get();
            user = userService.getUserByUserName(userDetails.getUsername());

            Mac2User mac2User = new Mac2User(macAddress, user);
            macService.save(mac2User);
        }
        authenticatedUser = user;
        return user;
    }


    public void logout() {
        authenticatedUser = null;
        authenticationContext.logout();
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
