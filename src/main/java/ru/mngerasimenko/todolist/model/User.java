package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import ru.mngerasimenko.todolist.crypto.EncryptedStringConverter;

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

    @Column(name = "email", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String email;

    /**
     * HMAC-SHA256 blind index для поиска по зашифрованному email.
     * Unique constraint перенесён сюда (зашифрованный email не может быть unique — разный IV).
     */
    @Column(name = "email_hash", length = 64, unique = true)
    private String emailHash;

    @Column(name = "password", nullable = false)
    @NotBlank
    private String password;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
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
     * Количество отправленных напоминаний о неактивности (макс. 3).
     * Сбрасывается при повторной активности.
     */
    @Column(name = "reminder_count", nullable = false)
    private int reminderCount = 0;

    /**
     * Язык, на котором пользователь хочет получать email-письма (BCP-47).
     * Устанавливается при регистрации (RegisterRequest.locale → Accept-Language → "ru").
     * Меняется только через явный PATCH /api/users/me/email-locale (R-3 phase B.6).
     * Не синхронизируется с UI-локалью приложения автоматически (по UX-решению).
     */
    @Column(name = "preferred_email_locale", nullable = false, length = 8)
    private String preferredEmailLocale = "ru";

    /**
     * Флаг — отправлено ли 3-дневное onboarding-напоминание (Phase 3.3).
     * Ставится в true однократно при отправке через OnboardingReminderScheduler,
     * больше не сбрасывается (юзер получает напоминание один раз на устройство).
     * Отдельно от 7-дневного reminder'а — у того свой счётчик reminderCount.
     */
    @Column(name = "onboarding_reminder_sent", nullable = false)
    private boolean onboardingReminderSent = false;

    /**
     * Пользователь явно отказался от reminder-напоминаний (3d и 7d одновременно).
     * Ставится в true через GET /api/users/unsubscribe-reminder?token=...
     * (forward-looking opt-out, см. fromIdeas/response_phase33_unsubscribe_risk_2026-05-17.md).
     * Сбрасывает все будущие reminder-отправки на email + push.
     */
    @Column(name = "reminder_opt_out", nullable = false)
    private boolean reminderOptOut = false;

    /**
     * Одноразовый токен для отписки от reminder'ов через email-ссылку.
     * Генерируется при отправке каждого reminder'а (3d или 7d), кладётся в footer-link
     * email-шаблона. При hit'е /api/users/unsubscribe-reminder?token=... сервер
     * валидирует, ставит reminder_opt_out=true и очищает токен.
     */
    @Column(name = "unsubscribe_token", length = 64)
    private String unsubscribeToken;

    // Sort preferences (Phase Nadezda-001).
    // ВАЖНО: 4 поля ниже инициализируются через field initializer ("CREATED_AT"/"DESC").
    // Не убирай initializer и не добавляй конструктор без явного set этих 4 полей —
    // колонки NOT NULL, при null-insert получишь SQL exception на saveAndFlush.

    /**
     * Режим сортировки списков задач: MANUAL, ALPHABETICAL, CREATED_AT.
     * Управляется PATCH /api/users/me/sort-preferences.
     */
    @Column(name = "lists_sort_mode", length = 20, nullable = false)
    private String listsSortMode = "CREATED_AT";

    /**
     * Направление сортировки списков задач: ASC или DESC.
     */
    @Column(name = "lists_sort_direction", length = 4, nullable = false)
    private String listsSortDirection = "DESC";

    /**
     * Режим сортировки задач внутри списка: MANUAL, ALPHABETICAL, CREATED_AT.
     */
    @Column(name = "todos_sort_mode", length = 20, nullable = false)
    private String todosSortMode = "CREATED_AT";

    /**
     * Направление сортировки задач: ASC или DESC.
     */
    @Column(name = "todos_sort_direction", length = 4, nullable = false)
    private String todosSortDirection = "DESC";

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

    public String getEmailHash() {
        return emailHash;
    }

    public void setEmailHash(String emailHash) {
        this.emailHash = emailHash;
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

    public String getPreferredEmailLocale() {
        return preferredEmailLocale;
    }

    public void setPreferredEmailLocale(String preferredEmailLocale) {
        this.preferredEmailLocale = preferredEmailLocale;
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

    public int getReminderCount() {
        return reminderCount;
    }

    public void setReminderCount(int reminderCount) {
        this.reminderCount = reminderCount;
    }

    public boolean isOnboardingReminderSent() {
        return onboardingReminderSent;
    }

    public void setOnboardingReminderSent(boolean onboardingReminderSent) {
        this.onboardingReminderSent = onboardingReminderSent;
    }

    public boolean isReminderOptOut() {
        return reminderOptOut;
    }

    public void setReminderOptOut(boolean reminderOptOut) {
        this.reminderOptOut = reminderOptOut;
    }

    public String getUnsubscribeToken() {
        return unsubscribeToken;
    }

    public void setUnsubscribeToken(String unsubscribeToken) {
        this.unsubscribeToken = unsubscribeToken;
    }

    public String getListsSortMode() {
        return listsSortMode;
    }

    public void setListsSortMode(String listsSortMode) {
        this.listsSortMode = listsSortMode;
    }

    public String getListsSortDirection() {
        return listsSortDirection;
    }

    public void setListsSortDirection(String listsSortDirection) {
        this.listsSortDirection = listsSortDirection;
    }

    public String getTodosSortMode() {
        return todosSortMode;
    }

    public void setTodosSortMode(String todosSortMode) {
        this.todosSortMode = todosSortMode;
    }

    public String getTodosSortDirection() {
        return todosSortDirection;
    }

    public void setTodosSortDirection(String todosSortDirection) {
        this.todosSortDirection = todosSortDirection;
    }
}
