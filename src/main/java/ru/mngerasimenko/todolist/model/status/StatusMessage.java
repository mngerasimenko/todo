package ru.mngerasimenko.todolist.model.status;

public class StatusMessage extends Status {
    private String message;

    public StatusMessage(String status, String message) {
        super(status);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
