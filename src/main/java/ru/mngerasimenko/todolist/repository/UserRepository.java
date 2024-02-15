package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mngerasimenko.todolist.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

    User getUserByEmail(String email);

    User getUserByEmailAndPassword(String email, String password);
}
