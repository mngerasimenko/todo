package ru.mngerasimenko.todolist.featureflags;

/**
 * Запрос к несуществующему feature-флагу. Маскируется в 404 для /api/admin/**.
 */
public class FeatureFlagNotFoundException extends RuntimeException {
    public FeatureFlagNotFoundException(String name) {
        super("Feature flag not found: " + name);
    }
}
