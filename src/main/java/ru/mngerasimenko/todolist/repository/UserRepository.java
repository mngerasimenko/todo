package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.User;

/**
 * Репозиторий для работы с пользователями (таблица todo_users).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    User getUserByEmail(String email);

    User getUserById(Long id);

    User getUserByName(String userName);

    User getUserByAuthId(String authId);
}
