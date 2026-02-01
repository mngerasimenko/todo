package ru.mngerasimenko.todolist.service;

import io.micrometer.common.util.StringUtils;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.List;

@Service
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User save(User user) {
        if (user.getId() == null) {
            User userByEmail = repository.getUserByEmail(user.getEmail());
            if (userByEmail != null) {
                return null;
            }
        }
        return repository.save(user);
    }

    public User getUser(User user) {
        if (StringUtils.isBlank(user.getEmail()) || StringUtils.isBlank(user.getPassword())) {
            return null;
        }
        return repository.getUserByEmailAndPassword(user.getEmail(), user.getPassword());
    }

    public User getUser(Long id) {
        return id == null ? null : repository.getUserById(id);
    }

    public User getUser(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        return repository.getUserByEmail(email);
    }

    public List<User> getAll() {
        return repository.findAll();
    }

    public void delete(long id) {
        repository.deleteById(id);
    }

    public User getUserByUserName(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        return repository.getUserByName(userName);
    }

    public User getUserByAuthId(String authId) {
        if (StringUtils.isBlank(authId)) {
            return null;
        }
        return repository.getUserByAuthId(authId);
    }

}
