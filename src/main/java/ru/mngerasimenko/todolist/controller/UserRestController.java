package ru.mngerasimenko.todolist.controller;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.UserService;
import ru.mngerasimenko.todolist.model.status.Status;
import ru.mngerasimenko.todolist.model.status.StatusLogin;

import static ru.mngerasimenko.todolist.settings.Constants.*;

@RestController
@RequestMapping("/api")
public class UserRestController {
    private final UserService userService;

    public UserRestController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    public List<User> showAll() {
        return userService.getAll();
    }

    @PostMapping("/register")
    public Status register(@RequestBody User user) {
        User newUser = userService.save(user);
        if (newUser == null) {
            return new Status(ALREADY_EXIST);
        }
        return new Status(CREATED);
    }

    @PostMapping("/login")
    public Status login(@RequestBody User user) {
        User foundUser = userService.getUser(user);
        if (foundUser == null) {
            foundUser = userService.getUser(user.getEmail());
            if (foundUser != null) {
                return new Status(WRONG_PASSWORD);
            } else {
                return new Status(NO_USER);
            }
        }
        return new StatusLogin(AUTHORIZE_SUCCESS, foundUser.getId(), foundUser.getName());
    }

    public User update(@RequestBody User user) {
        return userService.save(user);
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable int id) {
        userService.delete(id);
        return "User with ID = " + id + " was deleted";
    }
}
