package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import ru.mngerasimenko.todolist.crypto.EncryptedStringConverter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Список задач — пространство для совместной работы над задачами.
 * Пользователи подключаются к списку по invite-ссылке.
 */
@Entity
@Table(name = "task_list")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class TaskList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String name;

    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    @JsonIgnore
    private User creator;

    @Column(name = "creator_id", insertable = false, updatable = false)
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "taskList", fetch = FetchType.LAZY)
    private List<TaskListUser> taskListUsers = new ArrayList<>();

    /**
     * Версия записи для оптимистичной блокировки.
     * Hibernate автоматически инкрементирует при каждом UPDATE.
     */
    @Version
    @Column(name = "version")
    private Long version;

    public TaskList() {
    }

    public TaskList(String name, User creator) {
        this.name = name;
        this.creator = creator;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public User getCreator() {
        return creator;
    }

    public void setCreator(User creator) {
        this.creator = creator;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

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
