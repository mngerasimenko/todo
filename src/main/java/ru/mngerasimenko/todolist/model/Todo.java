package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "todo")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    @NotBlank
    @Size(min = 2, max = 120)
    private String name;

    /**
     * Дата создания задачи (было: date_time).
     */
    @Column(name = "created_at", nullable = false)
    @NotNull
    private LocalDateTime createdAt;

    /**
     * Дата выполнения задачи. Устанавливается при done=true, очищается при done=false.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "done", nullable = false)
    @NotNull
    private Boolean done;

    /**
     * Приватная задача — видна только создателю.
     */
    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    /**
     * Создатель задачи.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonBackReference
    @NotNull
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    /**
     * Пользователь, выполнивший задачу. Nullable.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completor_user_id")
    private User completorUser;

    @Column(name = "completor_user_id", insertable = false, updatable = false)
    private Long completorUserId;

    /**
     * Список задач, к которому принадлежит задача.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "list_id", nullable = false)
    @NotNull
    private TaskList taskList;

    @Column(name = "list_id", insertable = false, updatable = false)
    private Long listId;

    public Todo() {
    }

    public Todo(User user) {
        this.user = user;
    }

    public Todo(String title) {
        this(null, title, null, false);
    }

    public Todo(String title, User user) {
        this(null, title, user, false);
    }

    public Todo(Long id, String title, User user, Boolean done) {
        this.id = id;
        this.name = title;
        this.user = user;
        this.done = done;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonIgnore
    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
        if (user != null) {
            this.userId = user.getId();
        }
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public boolean getIsPrivate() {
        return isPrivate;
    }

    public void setIsPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    @JsonIgnore
    public boolean isNew() {
        return id == null;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @JsonIgnore
    public User getCompletorUser() {
        return completorUser;
    }

    public void setCompletorUser(User completorUser) {
        this.completorUser = completorUser;
        if (completorUser != null) {
            this.completorUserId = completorUser.getId();
        } else {
            this.completorUserId = null;
        }
    }

    public Long getCompletorUserId() {
        return completorUserId;
    }

    public void setCompletorUserId(Long completorUserId) {
        this.completorUserId = completorUserId;
    }

    @JsonIgnore
    public TaskList getTaskList() {
        return taskList;
    }

    public void setTaskList(TaskList taskList) {
        this.taskList = taskList;
        if (taskList != null) {
            this.listId = taskList.getId();
        }
    }

    public Long getListId() {
        return listId;
    }

    public void setListId(Long listId) {
        this.listId = listId;
    }
}
