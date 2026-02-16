package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.Todo;

import java.util.List;

@Repository
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findAllByUserId(long userId);

    List<Todo> findAllByUserIdAndNameContainingIgnoreCase(long userId, String title);

    Todo findByName(String title);

    List<Todo> findAllByUserIdAndDoneOrderByIdDesc(long userId, boolean done);

    Todo findByIdAndUserId(long id, long userId);

    @Modifying
    void deleteByUserIdAndId(long userId, long todoId);

    List<Todo> findByUserId(Long userId);

    List<Todo> findByUserIdAndDone(Long userId, boolean done);

    void deleteByUserId(Long userId);

}
