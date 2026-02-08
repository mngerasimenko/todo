package ru.mngerasimenko.todolist.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.validation.constraints.NotBlank;

//@JsonIgnoreProperties(value = {"hibernateLazyInitializer", "handler"})
//@Entity
//@Table(name = "mac2user")
public class Mac2User {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(name = "mac", nullable = false, unique = true)
//    @NotBlank
//    private String macAddress;
//
//    @ManyToOne(fetch = FetchType.EAGER)
//    @JoinColumn(name = "user_id", nullable = false)
//    @OnDelete(action = OnDeleteAction.CASCADE)
//    private User user;
//
//    public Mac2User(String macAddress, User user) {
//        this.macAddress = macAddress;
//        this.user = user;
//    }
//
//    public Mac2User() {
//
//    }
//
//    public void setId(Long id) {
//        this.id = id;
//    }
//
//    public Long getId() {
//        return id;
//    }
//
//    public String getMacAddress() {
//        return macAddress;
//    }
//
//    public void setMacAddress(String macAddress) {
//        this.macAddress = macAddress;
//    }
//
//    public User getUser() {
//        return user;
//    }
//
//    public void setUser(User user) {
//        this.user = user;
//    }
}
