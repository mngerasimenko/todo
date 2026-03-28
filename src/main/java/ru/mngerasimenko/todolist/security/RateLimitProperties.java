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

    /** Включение/выключение rate limiting в рантайме (для тестов). По умолчанию включён. */
    private boolean enabled = true;

    /** Заголовок для получения реального IP клиента от доверенного reverse proxy (nginx). */
    private String clientIpHeader = "X-Real-IP";

    private EndpointLimit login = new EndpointLimit(5, 60);
    private EndpointLimit register = new EndpointLimit(3, 3600);
    private EndpointLimit refresh = new EndpointLimit(10, 60);
    private EndpointLimit forgotPassword = new EndpointLimit(3, 3600);
    private EndpointLimit verifyEmail = new EndpointLimit(10, 60);
    private EndpointLimit resetPassword = new EndpointLimit(10, 60);
    private EndpointLimit resendVerification = new EndpointLimit(3, 3600);
    private EndpointLimit changeEmail = new EndpointLimit(3, 3600);
    private EndpointLimit logout = new EndpointLimit(10, 60);
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
