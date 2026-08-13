package ru.mngerasimenko.todolist.featureflags;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;
import ru.mngerasimenko.todolist.repository.FeatureFlagOverrideRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет то единственное, ради чего существует вся ветка: переключение флага переживает
 * перезапуск приложения.
 *
 * <p>Почему против настоящей Postgres, а не на моках: unit-тесты проверяют «мы позвали save»,
 * и этого недостаточно. Ревью показало цену — правка, из-за которой `save` падал на каждом
 * вызове (`updated_at = null` против `NOT NULL`), прошла и весь unit-набор, и интеграционный,
 * оставив ERROR в логе и полностью неработающую долговечность. Здесь значение реально пишется в
 * таблицу и реально читается обратно новым экземпляром хранилища — это и есть «пережило рестарт».
 */
@Tag("integration")
class FeatureFlagOverrideDurabilityTest extends AbstractIntegrationTest {

    @Autowired
    private FeatureFlagOverrideRepository overrideRepository;

    @Autowired
    private Environment environment;

    @AfterEach
    void cleanUp() {
        overrideRepository.deleteAll();
    }

    /** Новый экземпляр поверх той же БД = то, что происходит при рестарте процесса. */
    private FeatureFlagStore restarted() {
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);
        store.loadPersistedOverrides();
        return store;
    }

    @Test
    void featureFlagOverrideSurvivesARestart() {
        FeatureFlagStore before = new FeatureFlagStore(environment, overrideRepository);

        assertTrue(before.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test"),
                "переключение флага фичи обязано сохраняться");

        FeatureFlagStore afterRestart = restarted();

        assertFalse(afterRestart.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP),
                "после рестарта выключенная фича вернулась сама — ровно то, что чинила эта ветка");
        // И источник тоже: значение может пережить рестарт, а пульт при этом показывать RUNTIME
        // («слетит на рестарте»). Набор этого не замечал — проверял только значение.
        assertEquals(FlagSource.PERSISTED,
                afterRestart.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source(),
                "восстановленный из БД override обязан показываться как PERSISTED");
    }

    @Test
    void resetBringsTheFlagBackAcrossARestart() {
        FeatureFlagStore before = new FeatureFlagStore(environment, overrideRepository);
        before.set(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY, false, "admin@test");
        assertFalse(restarted().isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY));

        before.reset(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY);

        assertTrue(restarted().isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY),
                "снятый override не должен воскресать из БД");
        assertTrue(overrideRepository.findAll().isEmpty(), "строка обязана исчезнуть, а не остаться");
    }

    @Test
    void savedRowCarriesWhoAndWhen() {
        // Аудит — не украшение: при разборе инцидента это единственный способ понять,
        // кто и когда выключил функцию.
        new FeatureFlagStore(environment, overrideRepository)
                .set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");

        var row = overrideRepository.findById(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName())
                .orElseThrow(() -> new AssertionError("строка не сохранилась"));
        assertEquals("admin@test", row.getUpdatedBy());
        assertFalse(row.isEnabled());
        assertTrue(row.getUpdatedAt() != null, "updated_at обязателен — колонка NOT NULL");
    }

    @Test
    void protectionFlagOverrideIsDeliberatelyNotDurable() {
        FeatureFlagStore before = new FeatureFlagStore(environment, overrideRepository);

        assertFalse(before.set(FeatureFlag.RATE_LIMIT, false, "admin@test"),
                "переключение защиты не должно сохраняться");
        assertFalse(before.isEnabled(FeatureFlag.RATE_LIMIT), "но подействовать обязано немедленно");

        assertTrue(restarted().isEnabled(FeatureFlag.RATE_LIMIT),
                "снятая защита обязана восстановиться сама после рестарта");
    }

    @Test
    void persistedOverrideIsDistinguishableFromAnInProcessOne() {
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);

        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");
        store.set(FeatureFlag.RATE_LIMIT, false, "admin@test");

        // Пульт обязан показывать разницу: одно переживёт деплой, другое нет.
        assertEquals(FlagSource.PERSISTED,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
        assertEquals(FlagSource.RUNTIME,
                store.snapshot().get(FeatureFlag.RATE_LIMIT).source());
    }

    @Test
    void protectionFlagKeepsItsOldBehaviourEndToEnd() {
        // Прямая проверка того, что ветка НЕ сломала прежние флаги. Полный цикл rate-limit:
        // значение из env → ручное переключение действует немедленно → рестарт возвращает env
        // (а не дефолт и не сохранённое значение) → в таблице после всего этого пусто.
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);

        // 1. Ручное выключение действует сразу.
        store.set(FeatureFlag.RATE_LIMIT, false, "admin@test");
        assertFalse(store.isEnabled(FeatureFlag.RATE_LIMIT));

        // 2. Никаких следов в БД: процессный флаг туда не пишется вовсе.
        assertTrue(overrideRepository.findAll().isEmpty(),
                "переключение флага защиты не должно попадать в БД");

        // 3. Рестарт восстанавливает защиту сам — ради этого класс PROCESS и существует.
        assertTrue(restarted().isEnabled(FeatureFlag.RATE_LIMIT));

        // 4. Сброс тоже не трогает БД и возвращает значение к env/дефолту.
        assertTrue(store.reset(FeatureFlag.RATE_LIMIT));
        assertTrue(store.isEnabled(FeatureFlag.RATE_LIMIT));
        assertTrue(overrideRepository.findAll().isEmpty());
    }

    @Test
    void processFlagsNeverLeaveRowsBehind() {
        // Оба процессных флага разом: сколько ни переключай, таблица остаётся пустой.
        // Если кто-то переведёт защитный флаг в PERSISTENT, тест это заметит.
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);

        store.set(FeatureFlag.RATE_LIMIT, false, "admin@test");
        store.set(FeatureFlag.RESPONSE_CACHE, false, "admin@test");
        store.set(FeatureFlag.RATE_LIMIT, true, "admin@test");
        store.reset(FeatureFlag.RESPONSE_CACHE);

        assertTrue(overrideRepository.findAll().isEmpty(),
                "флаги защиты оставили строки в БД — их выключение переживёт рестарт, чего быть не должно");
    }

    @Test
    void featureAndProtectionFlagsDoNotInterfere() {
        // Смешанный сценарий: выключаем и фичу, и защиту. После рестарта фича обязана остаться
        // выключенной, защита — вернуться. Раздельность классов проверяется именно так.
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);
        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");
        store.set(FeatureFlag.RATE_LIMIT, false, "admin@test");

        FeatureFlagStore afterRestart = restarted();

        assertFalse(afterRestart.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP), "фича вернулась сама");
        assertTrue(afterRestart.isEnabled(FeatureFlag.RATE_LIMIT), "защита не восстановилась");
    }

    @Test
    void repeatToggleOfTheSameFlagOverwritesTheSameRow() {
        FeatureFlagStore store = new FeatureFlagStore(environment, overrideRepository);
        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");
        assertTrue(store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, true, "admin@test"),
                "second toggle of the same flag must still persist");
        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");
        assertEquals(1, overrideRepository.findAll().size());
        assertFalse(restarted().isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
    }
}
