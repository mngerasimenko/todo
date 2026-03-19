package ru.mngerasimenko.todolist.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Конфигурация приложения (версия, минимальная версия Android, CORS origins, лимиты подписки).
 */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String version = "0.0.1";
    private int minAndroidVersion = 1;
    private List<String> corsOrigins = List.of();

    /**
     * Глобальный флаг включения проверки лимитов подписки.
     * false — лимиты не применяются (бета-этап).
     * true — лимиты применяются (Freemium-этап).
     */
    private boolean subscriptionEnforcementEnabled = false;

    /**
     * Лимиты для бесплатной подписки (FREE).
     */
    private SubscriptionLimits freeLimits = new SubscriptionLimits();

    /**
     * Лимиты для PRO/BETA подписки (-1 = безлимит).
     */
    private SubscriptionLimits proLimits = new SubscriptionLimits(-1, -1, -1, true);

    /**
     * Настраиваемые лимиты подписки.
     */
    @Getter
    @Setter
    public static class SubscriptionLimits {
        /** Максимальное количество списков (-1 = безлимит) */
        private int maxLists = 2;
        /** Максимальное количество задач в списке (-1 = безлимит) */
        private int maxTasksPerList = 30;
        /** Максимальное количество участников в списке (-1 = безлимит) */
        private int maxMembersPerList = 3;
        /** Доступны ли приватные задачи */
        private boolean privateTasksAllowed = false;

        public SubscriptionLimits() {
        }

        public SubscriptionLimits(int maxLists, int maxTasksPerList, int maxMembersPerList, boolean privateTasksAllowed) {
            this.maxLists = maxLists;
            this.maxTasksPerList = maxTasksPerList;
            this.maxMembersPerList = maxMembersPerList;
            this.privateTasksAllowed = privateTasksAllowed;
        }
    }
}
