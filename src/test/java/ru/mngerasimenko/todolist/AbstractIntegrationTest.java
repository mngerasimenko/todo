package ru.mngerasimenko.todolist;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Базовый класс для интеграционных тестов с реальной PostgreSQL через TestContainers.
 * Контейнер запускается один раз (singleton) и живёт до завершения JVM.
 * Это позволяет переиспользовать контейнер между всеми тест-классами,
 * избегая проблемы с кэшированным Spring-контекстом и остановленным контейнером.
 * Хост Docker задаётся через системное свойство для совместимости с Docker Desktop на Windows.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        // Docker Desktop на Windows требует API версии 1.44+ (в версии 29.x — 1.53).
        // docker-java в TestContainers по умолчанию использует 1.32, что вызывает HTTP 400.
        // Устанавливаем совместимую версию API и TCP-хост через системные свойства docker-java.
        // Оба свойства — обход для Docker Desktop на Windows, и ставить их можно ТОЛЬКО там.
        // api.version=1.53 требует Docker Engine 29.x; на CI-раннере (Linux, Docker 28.x) демон
        // отвергает слишком новую версию клиента, и Testcontainers падает с «Could not find a
        // valid Docker environment» — сообщение, которое указывает куда угодно, только не сюда.
        // Проверяем ОС явно: «DOCKER_HOST не задан» — плохой признак Windows, на Linux и macOS
        // он тоже обычно пуст, и такой разработчик получил бы и tcp://localhost:2375, и слишком
        // новую api.version.
        // Два НЕЗАВИСИМЫХ решения, связывать их нельзя: разработчик на Windows, задавший
        // DOCKER_HOST вручную, остался бы без api.version и получил бы HTTP 400 от демона.
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            System.setProperty("api.version", "1.53");
            if (System.getenv("DOCKER_HOST") == null || System.getenv("DOCKER_HOST").isBlank()) {
                System.setProperty("DOCKER_HOST", "tcp://localhost:2375");
            }
        }

        // Singleton-паттерн: контейнер стартует один раз и живёт до конца JVM.
        // @Container + @Testcontainers останавливает контейнер после каждого тест-класса,
        // но Spring-контекст кэшируется — следующий класс получает мёртвое соединение.
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Liquibase управляет схемой (как на проде), Hibernate не трогает DDL
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.jpa.show-sql", () -> "false");
        // Увеличенный пул для конкурентных тестов (20 потоков)
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "25");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "5");
    }
}
