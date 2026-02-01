package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    User getUserByEmail(String email);

    User getUserByEmailAndPassword(String email, String password);

    User getUserById(Long id);

    User getUserByName(String userName);

    User getUserByAuthId(String authId);
}
