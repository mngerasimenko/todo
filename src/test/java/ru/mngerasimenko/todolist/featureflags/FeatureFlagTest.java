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

    /**
     * Вся ценность флага почтового канала — в его дефолте. Тесты доставки живут в
     * TodoReminderSchedulerTest.DispatchDueReminders, и ни один из них дефолт не сторожит:
     * положительная сторона приходит из lenient-стаба в @BeforeEach, отрицательная —
     * из явного override в одном тесте. То есть без этой проверки правка одного символа
     * (false → true) прошла бы весь набор зелёной, и письма уехали бы на первом же
     * включении свипа на проде — 126 верифицированных адресов на 06.09.2026.
     */
    @Test
    void todoReminderEmail_IsDisabledByDefault() {
        assertThat(FeatureFlag.TODO_REMINDER_EMAIL.getName()).isEqualTo("app.todo-reminder-email.enabled");
        assertThat(FeatureFlag.TODO_REMINDER_EMAIL.getDefaultValue()).isFalse();
        assertThat(FeatureFlag.TODO_REMINDER_EMAIL.getOverrideLifetime()).isEqualTo(OverrideLifetime.PERSISTENT);
        assertThat(FeatureFlag.TODO_REMINDER_EMAIL.getAudience()).isEqualTo(Audience.SERVER);
    }

    @Test
    void clientDueDates_IsClientVisible() {
        assertThat(FeatureFlag.CLIENT_TODO_DUE_DATES.isClientVisible()).isTrue();
        assertThat(FeatureFlag.CLIENT_TODO_DUE_DATES.getName()).isEqualTo("client.todo.due-dates.enabled");
    }
}
