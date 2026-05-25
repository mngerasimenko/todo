package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Связь пользователя со списком задач (many-to-many).
 * Хранит роль пользователя внутри конкретного списка.
 */
@Entity
@Table(name = "task_list_user")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class TaskListUser {

    @EmbeddedId
    private TaskListUserId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("listId")
    @JoinColumn(name = "list_id")
    private TaskList taskList;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private TaskListRole role;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /**
     * Позиция списка в порядке отображения у конкретного юзера (per-user sorting).
     * Backfill через ROW_NUMBER PARTITION BY user_id ORDER BY joined_at (см. Liquibase 023b).
     * Изменяется через PATCH /api/lists/reorder.
     * <p>
     * Field initializer = 0 обязателен: DB default'у (Liquibase 023 defaultValueNumeric: 0)
     * Hibernate не доверяет — INSERT всегда содержит явное значение колонки. Без initializer
     * летит NOT NULL constraint violation при createList/acceptInvite, потому что конструктор
     * TaskListUser(TaskList, User, TaskListRole) поля position не трогает.
     */
    @Column(name = "position", nullable = false)
    private Integer position = 0;

    /**
     * Персональный цвет списка у конкретного юзера (#RRGGBB), per-user (Server R-5.1).
     * null — цвет не задан. Изменяется через PATCH /api/lists/{id}/personalization.
     * Раньше цвет был общим (task_list.color); та колонка осталась как legacy/источник backfill.
     */
    @Column(name = "color", length = 7)
    private String color;

    /**
     * Версия записи для оптимистичной блокировки.
     * Hibernate автоматически инкрементирует при каждом UPDATE.
     */
    @Version
    @Column(name = "version")
    private Long version;

    public TaskListUser() {
    }

    public TaskListUser(TaskList taskList, User user, TaskListRole role) {
        this.id = new TaskListUserId(taskList.getId(), user.getId());
        this.taskList = taskList;
        this.user = user;
        this.role = role;
        this.joinedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }

    public TaskListUserId getId() {
        return id;
    }

    public void setId(TaskListUserId id) {
        this.id = id;
    }

    public TaskList getTaskList() {
        return taskList;
    }

    public void setTaskList(TaskList taskList) {
        this.taskList = taskList;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public TaskListRole getRole() {
        return role;
    }

    public void setRole(TaskListRole role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
