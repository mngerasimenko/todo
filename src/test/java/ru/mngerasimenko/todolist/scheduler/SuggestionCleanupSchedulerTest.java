package ru.mngerasimenko.todolist.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.repository.TaskSuggestionRepository;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link SuggestionCleanupScheduler}: flag-gating, передача cleanup-days
 * и проглатывание ошибок (scheduled-метод не должен бросать наружу).
 */
@ExtendWith(MockitoExtension.class)
class SuggestionCleanupSchedulerTest {

    @Mock
    private TaskSuggestionRepository repository;

    @Mock
    private FeatureFlagStore flagStore;

    private SuggestionProperties properties;
    private SuggestionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new SuggestionProperties();
        scheduler = new SuggestionCleanupScheduler(repository, properties, flagStore);
    }

    @Test
    void cleanup_FlagDisabled_DoesNotDelete() {
        when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(false);

        scheduler.cleanup();

        verify(repository, never()).deleteOlderThanDays(anyInt());
    }

    @Test
    void cleanup_FlagEnabled_DeletesWithConfiguredDays() {
        when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(true);
        when(repository.deleteOlderThanDays(properties.getCleanupDays())).thenReturn(7);

        scheduler.cleanup();

        verify(repository, times(1)).deleteOlderThanDays(properties.getCleanupDays());
    }

    @Test
    void cleanup_RepositoryThrows_DoesNotPropagate() {
        when(flagStore.isEnabled(FeatureFlag.SUGGESTIONS)).thenReturn(true);
        when(repository.deleteOlderThanDays(anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        // Scheduled-метод обязан проглотить ошибку — иначе падает планировщик.
        assertThatCode(() -> scheduler.cleanup()).doesNotThrowAnyException();
    }
}
