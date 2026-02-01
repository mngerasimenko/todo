package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.model.Todo;

import java.util.List;

@Repository
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findAllByUserId(long userId);

    List<Todo> findAllByUserIdAndTitleContainingIgnoreCase(long userId, String title);

    Todo findByTitle(String title);

    List<Todo> findAllByUserIdAndDoneOrderByIdDesc(long userId, boolean done);

    Todo findByIdAndUserId(long id, long userId);

    @Modifying
    @Transactional
    int deleteByUserIdAndId(long userId, long todoId);

}
