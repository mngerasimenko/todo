package ru.mngerasimenko.todolist.repository;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class TodoRepositoryTest {

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskListRepository taskListRepository;

    private User testUser;
    private TaskList testTaskList;
    private Todo todo1;
    private Todo todo2;

    @BeforeEach
    void setUp() {
        todoRepository.deleteAll();
        taskListRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setAuthId("auth123");
        testUser.setName("Test User");
        testUser.setEmail("test@mail.ru");
        testUser.setPassword("password123");
        userRepository.save(testUser);

        testTaskList = new TaskList("TestList", "$2a$10$hashedPass");
        taskListRepository.save(testTaskList);

        todo1 = new Todo();
        todo1.setName("First Task");
        todo1.setUser(testUser);
        todo1.setTaskList(testTaskList);
        todo1.setCreatedAt(LocalDateTime.now().minusDays(1));
        todo1.setDone(false);

        todo2 = new Todo();
        todo2.setName("Second Task");
        todo2.setUser(testUser);
        todo2.setTaskList(testTaskList);
        todo2.setCreatedAt(LocalDateTime.now());
        todo2.setDone(true);

        todoRepository.save(todo1);
        todoRepository.save(todo2);
    }

    @Test
    void findAllByUserId_ReturnsAllTodosForUser() {
        List<Todo> todos = todoRepository.findAllByUserId(testUser.getId());
        assertThat(todos).hasSize(2);
        assertThat(todos)
                .extracting(todo -> todo.getUser().getId())
                .allMatch(id -> id.equals(testUser.getId()));
    }

    @Test
    void findAllByUserIdAndNameContainingIgnoreCase_ReturnsFilteredTodos() {
        List<Todo> todos = todoRepository.findAllByUserIdAndNameContainingIgnoreCase(testUser.getId(), "first");
        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).getName()).isEqualTo("First Task");
    }

    @Test
    void findByName_ReturnsTodoByName() {
        Todo found = todoRepository.findByName("First Task");
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("First Task");
    }

    @Test
    void findAllByUserIdAndDoneOrderByIdDesc_ReturnsTodosByDoneStatus() {
        List<Todo> todos = todoRepository.findAllByUserIdAndDoneOrderByIdDesc(testUser.getId(), true);
        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).isDone()).isTrue();
    }

    @Test
    void findByIdAndUserId_ReturnsTodoByIdAndUserId() {
        Todo found = todoRepository.findByIdAndUserId(todo1.getId(), testUser.getId());
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("First Task");
    }

    @Test
    void deleteByUserIdAndId_DeletesTodo() {
        todoRepository.deleteByUserIdAndId(testUser.getId(), todo1.getId());
        Todo found = todoRepository.findByIdAndUserId(todo1.getId(), testUser.getId());
        assertThat(found).isNull();
    }

    @Test
    void findByUserId_ReturnsTodosForUser() {
        List<Todo> todos = todoRepository.findByUserId(testUser.getId());
        assertThat(todos).hasSize(2);
    }

    @Test
    void findByUserIdAndDone_ReturnsTodosByDoneStatus() {
        List<Todo> todos = todoRepository.findByUserIdAndDone(testUser.getId(), false);
        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).isDone()).isFalse();
    }

    @Test
    void deleteByUserId_DeletesAllTodosForUser() {
        todoRepository.deleteByUserId(testUser.getId());
        List<Todo> todos = todoRepository.findByUserId(testUser.getId());
        assertThat(todos).isEmpty();
    }

    @Test
    void saveTodo_WithNullName_ThrowsException() {
        Todo todo = new Todo();
        todo.setName(null);
        todo.setUser(testUser);
        todo.setTaskList(testTaskList);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setDone(false);

        assertThatThrownBy(() -> todoRepository.saveAndFlush(todo))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Validation failed");
    }

    @Test
    void saveTodo_WithNullCreatedAt_ThrowsException() {
        Todo todo = new Todo();
        todo.setName("Valid Name");
        todo.setUser(testUser);
        todo.setTaskList(testTaskList);
        todo.setCreatedAt(null);
        todo.setDone(false);

        assertThatThrownBy(() -> todoRepository.saveAndFlush(todo))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Validation failed");
    }

    @Test
    void saveTodo_WithoutUser_ThrowsException() {
        Todo todo = new Todo();
        todo.setName("Valid Name");
        todo.setUser(null);
        todo.setTaskList(testTaskList);
        todo.setCreatedAt(LocalDateTime.now());
        todo.setDone(false);

        assertThatThrownBy(() -> todoRepository.saveAndFlush(todo))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Validation failed");
    }
}
