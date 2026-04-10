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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA-сущность пользователя (таблица todo_users).
 */
@Entity
@Table(name = "todo_users")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class User {

    public static final String SUBSCRIPTION_FREE = "FREE";
    public static final String SUBSCRIPTION_PRO = "PRO";
    public static final String SUBSCRIPTION_PRO_LIFETIME = "PRO_LIFETIME";
    public static final String SUBSCRIPTION_BETA = "BETA";

    private static final java.util.Set<String> VALID_SUBSCRIPTION_TYPES =
            java.util.Set.of(SUBSCRIPTION_FREE, SUBSCRIPTION_PRO,
                    SUBSCRIPTION_PRO_LIFETIME, SUBSCRIPTION_BETA);

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

    @Column(name = "name", nullable = false)
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
     * Дата регистрации пользователя.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Подтверждён ли email пользователя.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /**
     * SHA-256 хеш токена верификации email.
     */
    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    /**
     * Срок действия токена верификации.
     */
    @Column(name = "email_verification_expires_at")
    private LocalDateTime emailVerificationExpiresAt;

    /**
     * SHA-256 хеш токена сброса пароля.
     */
    @Column(name = "password_reset_token")
    private String passwordResetToken;

    /**
     * Срок действия токена сброса пароля.
     */
    @Column(name = "password_reset_expires_at")
    private LocalDateTime passwordResetExpiresAt;

    /**
     * Тип подписки: FREE, PRO, BETA.
     */
    @Column(name = "subscription_type", nullable = false, length = 20)
    private String subscriptionType = "FREE";

    /**
     * Дата окончания подписки. Null для FREE.
     */
    @Column(name = "subscription_expires_at")
    private LocalDateTime subscriptionExpiresAt;

    /**
     * Флаг бета-тестера. Бета-тестеры получают бонусы при переходе на Freemium.
     */
    @Column(name = "is_beta_tester", nullable = false)
    private boolean betaTester = false;

    /**
     * Время последней активности (логин или refresh токена).
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * Время последней отправки напоминания о неактивности.
     */
    @Column(name = "last_reminder_sent_at")
    private LocalDateTime lastReminderSentAt;

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

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getEmailVerificationToken() {
        return emailVerificationToken;
    }

    public void setEmailVerificationToken(String emailVerificationToken) {
        this.emailVerificationToken = emailVerificationToken;
    }

    public LocalDateTime getEmailVerificationExpiresAt() {
        return emailVerificationExpiresAt;
    }

    public void setEmailVerificationExpiresAt(LocalDateTime emailVerificationExpiresAt) {
        this.emailVerificationExpiresAt = emailVerificationExpiresAt;
    }

    public String getPasswordResetToken() {
        return passwordResetToken;
    }

    public void setPasswordResetToken(String passwordResetToken) {
        this.passwordResetToken = passwordResetToken;
    }

    public LocalDateTime getPasswordResetExpiresAt() {
        return passwordResetExpiresAt;
    }

    public void setPasswordResetExpiresAt(LocalDateTime passwordResetExpiresAt) {
        this.passwordResetExpiresAt = passwordResetExpiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    public void setSubscriptionType(String subscriptionType) {
        if (subscriptionType != null && !VALID_SUBSCRIPTION_TYPES.contains(subscriptionType)) {
            throw new IllegalArgumentException("Недопустимый тип подписки: " + subscriptionType);
        }
        this.subscriptionType = subscriptionType;
    }

    public LocalDateTime getSubscriptionExpiresAt() {
        return subscriptionExpiresAt;
    }

    public void setSubscriptionExpiresAt(LocalDateTime subscriptionExpiresAt) {
        this.subscriptionExpiresAt = subscriptionExpiresAt;
    }

    public boolean isBetaTester() {
        return betaTester;
    }

    public void setBetaTester(boolean betaTester) {
        this.betaTester = betaTester;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public LocalDateTime getLastReminderSentAt() {
        return lastReminderSentAt;
    }

    public void setLastReminderSentAt(LocalDateTime lastReminderSentAt) {
        this.lastReminderSentAt = lastReminderSentAt;
    }
}
