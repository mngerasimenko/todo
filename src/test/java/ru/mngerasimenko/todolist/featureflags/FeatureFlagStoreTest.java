package ru.mngerasimenko.todolist.featureflags;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void clientFlags_returnsOnlyClientOnes() {
        FeatureFlagStore store = new FeatureFlagStore(new MockEnvironment());

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
        FeatureFlagStore store = new FeatureFlagStore(new MockEnvironment());

        assertEquals(true, store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP.getName()));

        store.set(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP, false);

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
        FeatureFlagStore store = new FeatureFlagStore(env);

        assertEquals(false,
                store.clientFlags().get(FeatureFlag.CLIENT_SUGGESTIONS_HISTORY.getName()));
    }

    @Test
    void clientFlags_keysAreTheSameNamesTheAdminPutUses() {
        FeatureFlagStore store = new FeatureFlagStore(new MockEnvironment());

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
        FeatureFlagStore store = new FeatureFlagStore(env);

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
        FeatureFlagStore store = new FeatureFlagStore(env);

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
            new FeatureFlagStore(env).logUnresolvableFlags();
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
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(env);
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
            new FeatureFlagStore(env).logUnresolvableFlags();
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
}
