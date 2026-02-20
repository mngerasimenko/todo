package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Аккаунт — пространство для совместной работы над задачами.
 * Пользователи вступают в аккаунт по названию и паролю.
 */
@Entity
@Table(name = "account")
@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 128)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 128)
    @NotBlank
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "account", fetch = FetchType.LAZY)
    private List<AccountUser> accountUsers = new ArrayList<>();

    public Account() {
    }

    public Account(String name, String passwordHash) {
        this.name = name;
        this.passwordHash = passwordHash;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<AccountUser> getAccountUsers() {
        return accountUsers;
    }

    public void setAccountUsers(List<AccountUser> accountUsers) {
        this.accountUsers = accountUsers;
    }
}
