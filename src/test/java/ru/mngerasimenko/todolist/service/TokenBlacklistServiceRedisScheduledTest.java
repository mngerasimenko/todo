package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import ru.mngerasimenko.todolist.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что {@code cleanupFallback()} в {@link TokenBlacklistServiceRedis}
 * действительно зарегистрирован Spring как {@code @Scheduled}-задача.
 *
 * <p>Unit-тесты с Mockito этого не ловят — регистрация задач происходит в
 * {@code ScheduledAnnotationBeanPostProcessor} при старте Spring-контекста.
 * Если метод случайно переименовать, убрать {@code @Scheduled} или поломать
 * конфигурацию {@code @EnableScheduling} — этот тест упадёт.
 *
 * <p>Требует поднятый Spring-контекст + Redis + PostgreSQL,
 * поэтому помечен {@code @Tag("integration")} и запускается в профиле
 * {@code -Pintegration}.
 */
@Tag("integration")
class TokenBlacklistServiceRedisScheduledTest extends AbstractIntegrationTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        // Контейнер без пароля — явно перебиваем дефолт из application.properties
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void cleanupFallback_IsRegisteredAsScheduledTask() {
        // ScheduledAnnotationBeanPostProcessor зарегистрирован через @EnableScheduling
        // под именем internalScheduledAnnotationProcessor. По типу через @Autowired
        // он не виден — это инфраструктурный бин, — поэтому достаём его из контекста
        // явно по классу.
        ScheduledAnnotationBeanPostProcessor postProcessor =
                applicationContext.getBean(ScheduledAnnotationBeanPostProcessor.class);

        // ScheduledMethodRunnable.toString() имеет стабильный формат
        // "<fqcn>.<methodName>". В Spring 6.x раннабл может быть обёрнут
        // (OutcomeTrackingRunnable и т. п.), но toString() делегируется
        // к исходному ScheduledMethodRunnable и формат сохраняется.
        String expected = "ru.mngerasimenko.todolist.service."
                + "TokenBlacklistServiceRedis.cleanupFallback";

        List<String> allScheduled = postProcessor.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .map(task -> task.getRunnable().toString())
                .toList();

        assertThat(allScheduled)
                .as("В списке зарегистрированных @Scheduled-задач должен быть "
                        + "TokenBlacklistServiceRedis.cleanupFallback. "
                        + "Если его нет — проверь @EnableScheduling и аннотацию "
                        + "@Scheduled на методе.")
                .contains(expected);
    }
}
