package ru.mngerasimenko.todolist.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.model.Todo;

public interface TodoRepository extends JpaRepository<Todo, Long> {
    List<Todo> findAllByUserIdAndDoneOrderByDateTime(long userId, boolean done);

    Todo findByIdAndUserId(long id, long userId);

    @Modifying
    @Transactional
    void deleteByUserIdAndId(long userId, long shoppingId);

}
