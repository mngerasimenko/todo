package ru.mngerasimenko.todolist.featureflags;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagTest {

    @Test
    void todoReminders_IsDisabledByDefault() {
        assertThat(FeatureFlag.TODO_REMINDERS.getName()).isEqualTo("app.todo-reminders.enabled");
        assertThat(FeatureFlag.TODO_REMINDERS.getDefaultValue()).isFalse();
        assertThat(FeatureFlag.TODO_REMINDERS.getOverrideLifetime()).isEqualTo(OverrideLifetime.PERSISTENT);
        assertThat(FeatureFlag.TODO_REMINDERS.getAudience()).isEqualTo(Audience.SERVER);
    }

    @Test
    void clientDueDates_IsClientVisible() {
        assertThat(FeatureFlag.CLIENT_TODO_DUE_DATES.isClientVisible()).isTrue();
        assertThat(FeatureFlag.CLIENT_TODO_DUE_DATES.getName()).isEqualTo("client.todo.due-dates.enabled");
    }
}
