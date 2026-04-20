package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Проверяет, что feature-флаг rate-limit.storage корректно разводит реализации BucketProvider:
 * ровно один бин типа BucketProvider в контексте в любом из режимов.
 * Ловит дубликаты бинов, опечатки в @ConditionalOnProperty и забытые matchIfMissing.
 * Использует ApplicationContextRunner — легковесный контекст без полного SpringBoot-поднятия.
 */
class RateLimitBucketProviderContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BucketProviderInMemory.class, BucketProviderRedis.class);

    @Test
    void defaultMode_ActivatesInMemoryProviderOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BucketProvider.class);
            assertThat(context.getBean(BucketProvider.class))
                    .isInstanceOf(BucketProviderInMemory.class);
        });
    }

    @Test
    void memoryMode_ActivatesInMemoryProviderOnly() {
        contextRunner
                .withPropertyValues("rate-limit.storage=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(BucketProvider.class);
                    assertThat(context.getBean(BucketProvider.class))
                            .isInstanceOf(BucketProviderInMemory.class);
                });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redisMode_ActivatesRedisProviderOnly() {
        contextRunner
                .withPropertyValues("rate-limit.storage=redis")
                .withBean(LettuceBasedProxyManager.class, () -> (LettuceBasedProxyManager) mock(LettuceBasedProxyManager.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(BucketProvider.class);
                    assertThat(context.getBean(BucketProvider.class))
                            .isInstanceOf(BucketProviderRedis.class);
                });
    }
}
