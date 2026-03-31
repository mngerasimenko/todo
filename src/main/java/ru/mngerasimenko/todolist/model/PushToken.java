package ru.mngerasimenko.todolist.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * FCM push-токен устройства пользователя.
 * Один пользователь может иметь несколько устройств, каждое с уникальным deviceId.
 */
@Entity
@Table(name = "push_token")
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * FCM-токен устройства.
     */
    @Column(name = "fcm_token", nullable = false, length = 512)
    private String fcmToken;

    /**
     * Уникальный идентификатор устройства (Android ID или UUID).
     */
    @Column(name = "device_id", nullable = false, unique = true, length = 255)
    private String deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PushToken() {
    }

    public PushToken(User user, String fcmToken, String deviceId) {
        this.user = user;
        this.fcmToken = fcmToken;
        this.deviceId = deviceId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Геттеры и сеттеры

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getFcmToken() {
        return fcmToken;
    }

    public void setFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
