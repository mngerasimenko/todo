package ru.mngerasimenko.shoppinglist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mngerasimenko.shoppinglist.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
