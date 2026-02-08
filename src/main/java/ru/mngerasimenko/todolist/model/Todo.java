package ru.mngerasimenko.todolist.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
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
    @Column(name = "date_time", nullable = false)
    @NotNull
    private LocalDateTime dateTime;
    @Column(name = "done", nullable = false)
    @NotNull
    private Boolean done;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonBackReference
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    private Long authKey;

    public Todo() {
    }

    public Todo(long userId) {
        this();
        this.setAuthKey(userId);
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

    public void setTitle(String name) {
        this.name = name;
    }

    @JsonIgnore
    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public LocalDateTime getDateTime() {
        return dateTime;
    }

    public void setDateTime(LocalDateTime dateTime) {
        this.dateTime = dateTime;
    }

    public Boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long getAuthKey() {
        return authKey;
    }

    public void setAuthKey(Long authKey) {
        this.authKey = authKey;
    }

    @JsonIgnore
    public boolean isNew() {
        return id == null;
    }
}
