package ru.mngerasimenko.shoppinglist.controller;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.shoppinglist.entity.User;
import ru.mngerasimenko.shoppinglist.service.UserService;

@RestController
@RequestMapping("/api/users")
public class UserRestController {
    private final UserService userService;

    public UserRestController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("")
    public List<User> showAll() {
        return userService.getAll();
    }

    @PostMapping("")
    public User add(@RequestBody User user) {
        return userService.save(user);
    }

    @PutMapping("")
    public User update(@RequestBody User user) {
        return userService.save(user);
    }

    @DeleteMapping("/{id}")
    public String deleteEmployee(@PathVariable int id) {
        userService.delete(id);

        return "User with ID = " + id + " was deleted";
    }
}
