package ru.mngerasimenko.todolist.featureflags;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagStoreTest {

    @Test
    void isEnabled_noOverrideNoEnv_returnsEnumDefault() {
        FeatureFlagStore store = new FeatureFlagStore(new MockEnvironment());

        assertEquals(FeatureFlag.RATE_LIMIT.getDefaultValue(),
                store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void isEnabled_envOverridesEnumDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = new FeatureFlagStore(env);

        assertEquals(false, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void isEnabled_runtimeOverridesEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = new FeatureFlagStore(env);

        store.set(FeatureFlag.RATE_LIMIT, true);

        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void reset_removesRuntimeButKeepsEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = new FeatureFlagStore(env);

        store.set(FeatureFlag.RATE_LIMIT, true);
        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));

        store.reset(FeatureFlag.RATE_LIMIT);
        // env-override остаётся
        assertEquals(false, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void snapshot_containsAllFlagsWithCorrectSource() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = new FeatureFlagStore(env);
        store.set(FeatureFlag.INACTIVE_REMINDER, false);

        Map<FeatureFlag, FeatureFlagStore.Resolution> snapshot = store.snapshot();

        assertEquals(FeatureFlag.values().length, snapshot.size());

        // RATE_LIMIT → ENV
        assertEquals(FlagSource.ENV, snapshot.get(FeatureFlag.RATE_LIMIT).source());
        assertEquals(false, snapshot.get(FeatureFlag.RATE_LIMIT).value());

        // INACTIVE_REMINDER → RUNTIME
        assertEquals(FlagSource.RUNTIME, snapshot.get(FeatureFlag.INACTIVE_REMINDER).source());
        assertEquals(false, snapshot.get(FeatureFlag.INACTIVE_REMINDER).value());

        // PUSH_NOTIFICATIONS → DEFAULT (ничего не задавали)
        assertEquals(FlagSource.DEFAULT, snapshot.get(FeatureFlag.PUSH_NOTIFICATIONS).source());
        assertEquals(FeatureFlag.PUSH_NOTIFICATIONS.getDefaultValue(),
                snapshot.get(FeatureFlag.PUSH_NOTIFICATIONS).value());
    }

    @Test
    void findByName_knownFlag_returnsPresent() {
        assertTrue(FeatureFlag.findByName("rate-limit.enabled").isPresent());
        assertTrue(FeatureFlag.findByName("app.inactive-reminder.enabled").isPresent());
    }

    @Test
    void findByName_unknownFlag_returnsEmpty() {
        assertTrue(FeatureFlag.findByName("unknown").isEmpty());
        assertTrue(FeatureFlag.findByName("").isEmpty());
    }
}
