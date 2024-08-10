package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mngerasimenko.todolist.model.Mac2User;


public interface MacRepository extends JpaRepository<Mac2User, Long> {

    Mac2User findByUserId(long userId);

    Mac2User findByMacAddress(String macAddress);
}
