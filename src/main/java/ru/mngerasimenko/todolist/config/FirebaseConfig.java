package ru.mngerasimenko.todolist.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Инициализация Firebase Admin SDK для отправки push-уведомлений.
 * Отключается в тестах через app.firebase.enabled=false.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.firebase.enabled", havingValue = "true", matchIfMissing = false)
public class FirebaseConfig {

    @PostConstruct
    public void init() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                String credentialsPath = System.getenv("FIREBASE_CREDENTIALS_PATH");
                if (credentialsPath == null || credentialsPath.isBlank()) {
                    credentialsPath = "/todo/firebase-service-account.json";
                }

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(new FileInputStream(credentialsPath)))
                        .build();

                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK инициализирован");
            }
        } catch (IOException e) {
            log.warn("Firebase Admin SDK не инициализирован: {}. Push-уведомления отключены.", e.getMessage());
        }
    }
}
