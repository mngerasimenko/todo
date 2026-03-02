package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA-сущность пользователя (таблица todo_users).
 */
@Entity
@Table(name = "todo_users")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_id", nullable = false, unique = true)
    @NotBlank
    @Size(max = 128)
    private String authId;

    @Column(name = "email", nullable = false, unique = true)
    @Email
    @NotBlank
    @Size(max = 128)
    private String email;

    @Column(name = "password", nullable = false)
    @NotBlank
    @Size(min = 5, max = 128)
    private String password;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    /**
     * Цвет иконки задачи при создании (HEX, например #4285F4).
     */
    @Column(name = "created_task_color", nullable = false, length = 7)
    private String createdTaskColor = "#4285F4";

    /**
     * Цвет иконки задачи при выполнении (HEX, например #34A853).
     */
    @Column(name = "completed_task_color", nullable = false, length = 7)
    private String completedTaskColor = "#34A853";

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "user")
    @OrderBy("createdAt DESC")
    @JsonManagedReference
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<Todo> todoList = new ArrayList<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "user")
    private List<TaskListUser> taskListUsers = new ArrayList<>();

    /**
     * Версия записи для оптимистичной блокировки.
     * Hibernate автоматически инкрементирует при каждом UPDATE.
     */
    @Version
    @Column(name = "version")
    private Long version;

    public User() {
    }

    public User(String authId, String email, String password, String name) {
        this(null, authId, email, password, name);
    }

    public User(Long id, String authId, String email, String password, String name) {
        this.id = id;
        this.authId = authId;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthId() {
        return authId;
    }

    public void setAuthId(String authId) {
        this.authId = authId;
    }

    public String getCreatedTaskColor() {
        return createdTaskColor;
    }

    public void setCreatedTaskColor(String createdTaskColor) {
        this.createdTaskColor = createdTaskColor;
    }

    public String getCompletedTaskColor() {
        return completedTaskColor;
    }

    public void setCompletedTaskColor(String completedTaskColor) {
        this.completedTaskColor = completedTaskColor;
    }

    @JsonIgnore
    public List<Todo> getTodoList() {
        return todoList;
    }

    public void setTodoList(List<Todo> todoList) {
        this.todoList = todoList;
    }

    @JsonIgnore
    public List<TaskListUser> getTaskListUsers() {
        return taskListUsers;
    }

    public void setTaskListUsers(List<TaskListUser> taskListUsers) {
        this.taskListUsers = taskListUsers;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
