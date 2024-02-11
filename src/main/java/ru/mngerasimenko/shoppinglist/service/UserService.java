package ru.mngerasimenko.shoppinglist.service;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.shoppinglist.entity.User;
import ru.mngerasimenko.shoppinglist.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User save(User user) {
       return repository.save(user);
    }

    public List<User> getAll() {
        return repository.findAll();
    }

    public void delete(long id) {
        repository.deleteById(id);
    }
}
