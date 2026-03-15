package ru.mngerasimenko.todolist.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация лимитов запросов для rate limiting.
 */
@ConfigurationProperties(prefix = "rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private EndpointLimit login = new EndpointLimit(5, 60);
    private EndpointLimit register = new EndpointLimit(3, 3600);
    private EndpointLimit refresh = new EndpointLimit(10, 60);
    private EndpointLimit general = new EndpointLimit(100, 60);

    @Getter
    @Setter
    public static class EndpointLimit {
        private int requests;
        private int durationSeconds;

        public EndpointLimit() {
        }

        public EndpointLimit(int requests, int durationSeconds) {
            this.requests = requests;
            this.durationSeconds = durationSeconds;
        }
    }
}
