package ru.mngerasimenko.todolist.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Составной первичный ключ для таблицы account_user.
 */
@Embeddable
public class AccountUserId implements Serializable {

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "user_id")
    private Long userId;

    public AccountUserId() {
    }

    public AccountUserId(Long accountId, Long userId) {
        this.accountId = accountId;
        this.userId = userId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountUserId)) return false;
        AccountUserId that = (AccountUserId) o;
        return Objects.equals(accountId, that.accountId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, userId);
    }
}
