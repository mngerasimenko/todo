package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Связь пользователя с аккаунтом (many-to-many).
 * Хранит роль пользователя внутри конкретного аккаунта.
 */
@Entity
@Table(name = "account_user")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class AccountUser {

    @EmbeddedId
    private AccountUserId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("accountId")
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AccountRole role;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    public AccountUser() {
    }

    public AccountUser(Account account, User user, AccountRole role) {
        this.id = new AccountUserId(account.getId(), user.getId());
        this.account = account;
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

    public AccountUserId getId() {
        return id;
    }

    public void setId(AccountUserId id) {
        this.id = id;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
