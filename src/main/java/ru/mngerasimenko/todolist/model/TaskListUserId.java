package ru.mngerasimenko.todolist.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Составной первичный ключ для таблицы task_list_user.
 */
@Embeddable
public class TaskListUserId implements Serializable {

    @Column(name = "list_id")
    private Long listId;

    @Column(name = "user_id")
    private Long userId;

    public TaskListUserId() {
    }

    public TaskListUserId(Long listId, Long userId) {
        this.listId = listId;
        this.userId = userId;
    }

    public Long getListId() {
        return listId;
    }

    public void setListId(Long listId) {
        this.listId = listId;
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
        if (!(o instanceof TaskListUserId that)) return false;
        return Objects.equals(listId, that.listId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(listId, userId);
    }
}
