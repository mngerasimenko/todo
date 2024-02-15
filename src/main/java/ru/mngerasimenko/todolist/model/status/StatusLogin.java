package ru.mngerasimenko.todolist.model.status;

public class StatusLogin extends Status {
    private String authKey;
    private String name;

    public StatusLogin(String status, Long userId, String name) {
        super(status);
        this.authKey = String.valueOf(userId);
        this.name = name;
    }

    public String getAuthKey() {
        return authKey;
    }

    public void setAuthKey(String authKey) {
        this.authKey = authKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
