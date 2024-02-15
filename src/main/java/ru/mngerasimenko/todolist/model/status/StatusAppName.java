package ru.mngerasimenko.todolist.model.status;

import ru.mngerasimenko.todolist.settings.Constants;

public class StatusAppName {
    private String appName;

    public StatusAppName() {
        this.appName = Constants.APP_NAME;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }
}
