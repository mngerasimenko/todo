package ru.mngerasimenko.todolist.featureflags;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.CannotCreateTransactionException;
import ru.mngerasimenko.todolist.model.FeatureFlagOverride;
import ru.mngerasimenko.todolist.repository.FeatureFlagOverrideRepository;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagStoreTest {

    /** Стор поверх окружения [env] с пустой БД override'ов — БД в этих тестах не предмет. */
    private static FeatureFlagStore storeOver(org.springframework.core.env.Environment env) {
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        return new FeatureFlagStore(env, repo);
    }


    @Test
    void isEnabled_noOverrideNoEnv_returnsEnumDefault() {
        FeatureFlagStore store = storeOver(new MockEnvironment());

        assertEquals(FeatureFlag.RATE_LIMIT.getDefaultValue(),
                store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void isEnabled_envOverridesEnumDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = storeOver(env);

        assertEquals(false, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void isEnabled_runtimeOverridesEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = storeOver(env);

        store.set(FeatureFlag.RATE_LIMIT, true, "admin@test");

        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void reset_removesRuntimeButKeepsEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = storeOver(env);

        store.set(FeatureFlag.RATE_LIMIT, true, "admin@test");
        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));

        store.reset(FeatureFlag.RATE_LIMIT);
        // env-override остаётся
        assertEquals(false, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void snapshot_containsAllFlagsWithCorrectSource() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.RATE_LIMIT.getName(), "false");
        FeatureFlagStore store = storeOver(env);
        store.set(FeatureFlag.INACTIVE_REMINDER, false, "admin@test");

        Map<FeatureFlag, FeatureFlagStore.Resolution> snapshot = store.snapshot();

        assertEquals(FeatureFlag.values().length, snapshot.size());

        // RATE_LIMIT → ENV
        assertEquals(FlagSource.ENV, snapshot.get(FeatureFlag.RATE_LIMIT).source());
        assertEquals(false, snapshot.get(FeatureFlag.RATE_LIMIT).value());

        // INACTIVE_REMINDER → PERSISTED: флаг фичи, его переключение сохранено в БД
        assertEquals(FlagSource.PERSISTED, snapshot.get(FeatureFlag.INACTIVE_REMINDER).source());
        assertEquals(false, snapshot.get(FeatureFlag.INACTIVE_REMINDER).value());

        // PUSH_NOTIFICATIONS → DEFAULT (ничего не задавали)
        assertEquals(FlagSource.DEFAULT, snapshot.get(FeatureFlag.PUSH_NOTIFICATIONS).source());
        assertEquals(FeatureFlag.PUSH_NOTIFICATIONS.getDefaultValue(),
                snapshot.get(FeatureFlag.PUSH_NOTIFICATIONS).value());
    }

    @Test
    void clientFlags_returnsOnlyClientOnes() {
        FeatureFlagStore store = storeOver(new MockEnvironment());

        Map<String, Boolean> flags = store.clientFlags();

        // Только Audience.CLIENT: серверные выключатели наружу не отдаём — их набор
        // и описания это операционная карта сервиса.
        // Имена ЛИТЕРАЛАМИ, а не через FeatureFlag.X.getName(): это контракт на проводе публичного
        // /api/status, и менять его можно только осознанно, руками, здесь же в тесте. Сверка через
        // isClientVisible() была бы тавтологией — она проверяет согласованность метода с самим
        // собой и молча пропускает главную ошибку: серверный флаг, по недосмотру помеченный
        // CLIENT. Тогда набор выключателей сервиса (rate-limit, кэш, планировщики) уехал бы
        // любому анонимному клиенту.
        assertEquals(
                Map.of("client.suggestions.history.enabled", true,
                        "client.suggestions.dedup.enabled", true),
                flags,
                "контракт клиентских флагов на проводе изменился");
        // Значения тоже литералами, не через getDefaultValue(): дефолт — это то, с чем живут ВСЕ
        // установки на первом запуске, офлайн и со старым сервером. Сверка с самим собой пропустила
        // бы правку одного символа в enum, которая молча выключает фичу у всех.
    }

    @Test
    void clientFlags_reflectRuntimeOverride() {
        FeatureFlagStore store = storeOver(new MockEnvironment());

        assertEquals(true, store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()));

        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");

        // Значение обязано разрешаться теми же правилами приоритета, что и серверные:
        // иначе аварийное выключение через админский PUT не доехало бы до клиента.
        assertEquals(false, store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()));
    }

    @Test
    void clientFlags_reflectEnv() {
        // Приоритет env обязан работать и для клиентских флагов: иначе значение, выставленное
        // в application.properties, доехало бы до сервера, но не до приложения.
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY.getName(), "false");
        FeatureFlagStore store = storeOver(env);

        assertEquals(false,
                store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY.getName()));
    }

    @Test
    void clientFlags_keysAreTheSameNamesTheAdminPutUses() {
        FeatureFlagStore store = storeOver(new MockEnvironment());

        // Клиент сопоставляет флаги по имени, и это же имя стоит в URL пульта
        // PUT /api/admin/flags/{name}/{value}. Разъедутся — выключатель перестанет работать.
        for (String name : store.clientFlags().keySet()) {
            assertTrue(FeatureFlag.findByName(name).isPresent(),
                    "имя " + name + " не находится через findByName");
        }
    }

    @Test
    void malformedEnvValue_fallsBackToDefaultInsteadOfThrowing() {
        // Опечатка в значении не должна ронять НИЧЕГО: клиентские флаги едут в публичный
        // /api/status, на котором висит splash приложения. Исключение отсюда = 500 на каждый
        // запуск у всех установок; падение на старте (была и такая версия) = рестарт-цикл с
        // недоступным API, потому что деплой поднимает приложение без health-check.
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), "notabool");
        FeatureFlagStore store = storeOver(env);

        assertEquals(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getDefaultValue(),
                store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
        assertEquals(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getDefaultValue(),
                store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()));
        assertEquals(FlagSource.DEFAULT,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
    }

    @Test
    void malformedEnvValue_doesNotBreakOtherFlags() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), "notabool");
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY.getName(), "false");
        FeatureFlagStore store = storeOver(env);

        assertEquals(false, store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY.getName()));
    }

    @Test
    void logUnresolvableFlags_reportsTheOffendingFlagAtErrorLevel() {
        // Лог — ЕДИНСТВЕННЫЙ сигнал о кривом значении: сервис не падает, /api/status отвечает
        // дефолтом, а снапшот показывает источник DEFAULT, неотличимо от «env не задан».
        // Поэтому проверяем не «не бросает», а что сообщение реально уходит и называет флаг.
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), "notabool");

        Logger logger = (Logger) LoggerFactory.getLogger(FeatureFlagStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            storeOver(env).logUnresolvableFlags();
        } finally {
            logger.detachAppender(appender);
        }

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), "ожидалась ровно одна ERROR-запись");
        assertTrue(errors.get(0).getFormattedMessage()
                        .contains(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()),
                "в сообщении нет имени флага: " + errors.get(0).getFormattedMessage());
    }

    @Test
    void springActuallyInvokesTheStartupCheck() {
        // Тесты выше зовут метод напрямую — они не заметят, если снять @PostConstruct и хук
        // перестанет вызываться вовсе. А это единственный сигнал о кривом значении.
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), "notabool");

        Logger logger = (Logger) LoggerFactory.getLogger(FeatureFlagStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(env);
            ctx.registerBean(FeatureFlagOverrideRepository.class, () -> repo);
            ctx.register(FeatureFlagStore.class);
            ctx.refresh(); // поднятие бина обязано дёрнуть хук
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(),
                "Spring не вызвал стартовую проверку — потерян @PostConstruct?");
    }

    @Test
    void logUnresolvableFlags_silentOnValidConfig() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), "false");

        Logger logger = (Logger) LoggerFactory.getLogger(FeatureFlagStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            storeOver(env).logUnresolvableFlags();
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(appender.list.isEmpty(), "нормальный конфиг не должен ничего логировать");
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

    // ===== Долговечные override'ы (переживают рестарт) =====

    private static FeatureFlagStore storeWith(FeatureFlagOverrideRepository repo) {
        return new FeatureFlagStore(new MockEnvironment(), repo);
    }

    @Test
    void set_persistsOverrideForFeatureFlags() {
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        FeatureFlagStore store = storeWith(repo);

        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");

        ArgumentCaptor<FeatureFlagOverride> saved = ArgumentCaptor.forClass(FeatureFlagOverride.class);
        verify(repo).save(saved.capture());
        assertEquals(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), saved.getValue().getName());
        assertEquals(false, saved.getValue().isEnabled());
        assertEquals("admin@test", saved.getValue().getUpdatedBy());
    }

    @Test
    void set_doesNotPersistProtectionFlags() {
        // rate-limit намеренно процессный: снятая защита обязана вернуться сама после рестарта.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        FeatureFlagStore store = storeWith(repo);

        store.set(FeatureFlag.RATE_LIMIT, false, "admin@test");

        assertEquals(false, store.isEnabled(FeatureFlag.RATE_LIMIT));
        verify(repo, never()).save(any());
    }

    @Test
    void reset_removesThePersistedOverride() {
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        FeatureFlagStore store = storeWith(repo);

        store.reset(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP);

        verify(repo).deleteById(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName());
    }

    @Test
    void reset_onProcessFlagNeverTouchesTheDatabase() {
        // Симметрично set(): возвращать защиту приходится, когда сервису плохо — нередко из-за
        // самой БД. Ждать connection-timeout здесь нечего: строк для таких флагов не пишется.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        assertEquals(true, storeWith(repo).reset(FeatureFlag.RATE_LIMIT));

        verify(repo, never()).deleteById(any());
    }

    @Test
    void startup_restoresPersistedOverrides() {
        // Собственно то, ради чего всё: выключенная фича не должна вернуться сама после деплоя.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of(new FeatureFlagOverride(
                FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), false, LocalDateTime.now(), "admin@test")));
        FeatureFlagStore store = storeWith(repo);

        store.loadPersistedOverrides();

        assertEquals(false, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
        // PERSISTED, а не RUNTIME: значение пришло из БД и переживёт следующий рестарт.
        assertEquals(FlagSource.PERSISTED,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
        assertEquals(false,
                store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()));
    }

    @Test
    void startup_ignoresOverridesOfUnknownFlagsAndKeepsLoadingTheRest() {
        // Флаг удалили из реестра, строка в БД осталась — источник истины реестр, а не БД.
        // Проверяем НАБЛЮДАЕМОЕ следствие: соседний валидный override обязан восстановиться.
        // Прошлая версия сверяла размер snapshot(), который всегда равен числу флагов, то есть
        // не могла упасть — а без guard'а тут NPE, и загрузка молча обрывалась на первой строке.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                new FeatureFlagOverride("some.retired.flag", false, LocalDateTime.now(), "admin@test"),
                new FeatureFlagOverride(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), false,
                        LocalDateTime.now(), "admin@test")));
        FeatureFlagStore store = storeWith(repo);

        store.loadPersistedOverrides();

        assertEquals(false, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP),
                "неизвестное имя оборвало загрузку остальных override'ов");
    }

    @Test
    void startup_ignoresOverridesOfFlagsThatBecameProcessScoped() {
        // Флаг переклассифицировали в PROCESS — сохранённое значение больше не действует,
        // иначе снятая когда-то защита воскресла бы из БД после рестарта.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of(new FeatureFlagOverride(
                FeatureFlag.RATE_LIMIT.getName(), false, LocalDateTime.now(), "admin@test")));
        FeatureFlagStore store = storeWith(repo);

        store.loadPersistedOverrides();

        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));
    }

    @Test
    void startup_survivesAnUnreachableDatabase() {
        // БД недоступна — сервис обязан подняться на env/дефолтах, а не упасть в рестарт-цикл.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenThrow(new CannotCreateTransactionException("no connection"));
        FeatureFlagStore store = storeWith(repo);

        store.loadPersistedOverrides();

        assertEquals(true, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
    }

    @Test
    void set_appliesInMemoryEvenIfPersistingFails() {
        // Команда админа должна подействовать немедленно: сохранить не вышло — работаем как
        // раньше, до ближайшего рестарта, но выключение происходит.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.save(any())).thenThrow(new CannotCreateTransactionException("no connection"));
        FeatureFlagStore store = storeWith(repo);

        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");

        assertEquals(false, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
    }

    @Test
    void everyFlagDeclaresItsOverrideLifetimeDeliberately() {
        // Процессные — только те, что прикрывают сервис: rate-limit и кэш ответов (он же
        // прикрывает путь аутентификации). Забытое выключение такого флага не должно жить вечно.
        // Список литералами: попадёт сюда флаг фичи по недосмотру — аварийное выключение начнёт
        // само отменяться на деплое, а это ровно то, ради чего вся ветка.
        Set<FeatureFlag> processScoped = EnumSet.of(FeatureFlag.RATE_LIMIT, FeatureFlag.RESPONSE_CACHE);
        for (FeatureFlag flag : FeatureFlag.values()) {
            OverrideLifetime expected = processScoped.contains(flag)
                    ? OverrideLifetime.PROCESS : OverrideLifetime.PERSISTENT;
            assertEquals(expected, flag.getOverrideLifetime(),
                    "флаг " + flag.getName() + " сменил класс долговечности");
        }
    }

    @Test
    void springInvokesTheOverrideReload() {
        // Тесты выше зовут loadPersistedOverrides() напрямую и не заметят, если снять
        // @PostConstruct — а без него сохранённые переключения не восстанавливаются вовсе,
        // то есть фича мертва при зелёном наборе тестов.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of(new FeatureFlagOverride(
                FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName(), false, LocalDateTime.now(), "admin@test")));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(new MockEnvironment());
            ctx.registerBean(FeatureFlagOverrideRepository.class, () -> repo);
            ctx.register(FeatureFlagStore.class);
            ctx.refresh();

            assertEquals(false, ctx.getBean(FeatureFlagStore.class)
                    .isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP),
                    "Spring не вызвал загрузку override'ов — потерян @PostConstruct?");
        }
    }

    @Test
    void reset_appliesInMemoryEvenIfTheDeleteFails() {
        // Зеркало set_appliesInMemoryEvenIfPersistingFails: снятие тоже обязано подействовать
        // немедленно, даже если удалить строку не вышло.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        doThrow(new CannotCreateTransactionException("no connection")).when(repo).deleteById(any());
        FeatureFlagStore store = storeWith(repo);
        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");

        assertEquals(false, store.reset(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP),
                "неудача удаления обязана быть видна вызывающему");
        assertEquals(true, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
    }

    @Test
    void set_reportsWhetherTheOverrideWillSurviveARestart() {
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        FeatureFlagStore store = storeWith(repo);

        assertEquals(true, store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test"));
        assertEquals(false, store.set(FeatureFlag.RATE_LIMIT, false, "admin@test"),
                "процессный флаг не сохраняется — админ должен это видеть");
        assertEquals(FlagSource.PERSISTED,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
        assertEquals(FlagSource.RUNTIME, store.snapshot().get(FeatureFlag.RATE_LIMIT).source());
    }

    @Test
    void failedPersistIsNotReportedAsDurable() {
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.save(any())).thenThrow(new CannotCreateTransactionException("no connection"));
        FeatureFlagStore store = storeWith(repo);

        assertEquals(false, store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test"));
        // И в пульте это тоже видно: RUNTIME, а не PERSISTED — значение слетит на рестарте.
        assertEquals(FlagSource.RUNTIME,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
    }

    @Test
    void failedResaveDropsThePersistedMarker() {
        // Тонкий случай: строка в БД уже есть, а перезапись падает. Значение в памяти новое,
        // в БД — старое, и после рестарта восстановится именно старое. Пульт обязан показать
        // RUNTIME, иначе он обещает пережить рестарт ровно противоположному значению.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        FeatureFlagStore store = storeWith(repo);
        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false, "admin@test");
        assertEquals(FlagSource.PERSISTED,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());

        when(repo.save(any())).thenThrow(new CannotCreateTransactionException("no connection"));
        assertEquals(false, store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, true, "admin@test"));

        assertEquals(true, store.isEnabled(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP));
        assertEquals(FlagSource.RUNTIME,
                store.snapshot().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP).source());
    }

    @Test
    void set_onProcessFlagNeverTouchesTheDatabase() {
        // Флаги защиты выключают, когда сервису плохо — нередко из-за самой БД. Поход в неё
        // повесил бы ответ на connection-timeout, пока переключение уже действует в памяти.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        storeWith(repo).set(FeatureFlag.RATE_LIMIT, false, "admin@test");

        verify(repo, never()).save(any());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void startup_keepsButIgnoresRowsOfReclassifiedFlags() {
        // Значение не действует — этого достаточно. Удалять нельзя: реклассификация обратима
        // (откат деплоя вернёт флаг в PERSISTENT), а удаление строки — нет.
        FeatureFlagOverrideRepository repo = mock(FeatureFlagOverrideRepository.class);
        when(repo.findAll()).thenReturn(List.of(new FeatureFlagOverride(
                FeatureFlag.RATE_LIMIT.getName(), false, LocalDateTime.now(), "admin@test")));
        FeatureFlagStore store = storeWith(repo);

        store.loadPersistedOverrides();

        assertEquals(true, store.isEnabled(FeatureFlag.RATE_LIMIT));
        verify(repo, never()).deleteById(any());
    }
}
