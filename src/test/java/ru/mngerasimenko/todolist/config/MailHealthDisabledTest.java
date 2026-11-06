package ru.mngerasimenko.todolist.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.mail.MailHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Встроенный mail health indicator Actuator не должен быть в контексте.
 *
 * <p>Без отключения (management.health.mail.enabled=false в application.properties)
 * Actuator дёргает реальный SMTP-коннект на каждый вызов /actuator/health — это
 * тысячи соединений в сутки и ложный DOWN контейнера при сбое внешнего почтовика.
 * Свой чек у приложения — {@code SmtpHealthScheduler}
 * (app.smtp-health-check.enabled), он от этого свойства не зависит и
 * smtp_healthy в /api/status остаётся его индикатором.</p>
 *
 * <p>Второй тест охраняет сам источник флага: контекст под test-профилем не
 * видит переопределений production-профиля и env, поэтому без проверки файлов
 * репо регрессия прошла бы все тесты тихо. Сканируются рекурсивно все
 * application-конфиги под {@code src/main/resources}, включая подкаталог
 * {@code config/} (Spring грузит {@code classpath:/config/} с приоритетом над
 * корнем) и форматы properties/yml/yaml/json, — плюс docker-compose-файлы,
 * Dockerfile, .env.example и deploy.yml. Текст нормализуется (регистр,
 * {@code _}/{@code -} → точка, сплющенная форма вложенных YAML/JSON-ключей,
 * склейка {@code \<EOL>}-продолжений строк свойств, декодирование
 * {@code \\uXXXX}) — иначе Spring резолвит management_health_mail_enabled,
 * management:/health:/mail:, flow-форму и escape-написание как
 * management.health.mail.enabled, а guard не увидел бы их вовсе. Граница:
 * всё, что вне git-файлов — живой {@code .env}, host-local compose/override,
 * shell-env оператора (включая pass-through {@code JAVA_TOOL_OPTIONS} в
 * compose), значения GitHub vars/secrets — файлами репо не видны.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MailHealthDisabledTest {

    private static final Path BASE_PROPERTIES = Path.of("src/main/resources/application.properties");
    /** База сканирования: флаг может жить в любом application-конфиге, кроме base. */
    private static final Path RESOURCES_DIR = Path.of("src/main/resources");

    @Autowired
    private ApplicationContext context;

    @Test
    void mailHealthIndicatorIsNotRegistered() {
        // Позитивный контроль: без JavaMailSender индикатор не появился бы и без
        // флага (@ConditionalOnBean) — тогда проверки ниже прошли бы вакуумно.
        assertThat(context.getBeansOfType(JavaMailSenderImpl.class))
                .as("ровно один SMTP-отправитель: при втором auto-config оборачивает "
                        + "индикатор в composite и проверка по типу бина слепнет")
                .hasSize(1);
        // Имя бина ловит и bare, и composite-форму; тип — bare-форму на случай
        // переименования: обе проверки должны сработать на включённом флаге.
        assertThat(context.containsBean("mailHealthContributor"))
                .as("бина mailHealthContributor не должно быть при выключенном флаге")
                .isFalse();
        assertThat(context.getBeansOfType(MailHealthIndicator.class))
                .as("MailHealthIndicator не должен быть зарегистрирован")
                .isEmpty();
    }

    @Test
    void flagLivesOnlyInBasePropertiesAndIsNotOverridden() throws IOException {
        assertThat(normalizeWithoutComments(read(BASE_PROPERTIES)))
                .as("флаг отключения mail health лежит в application.properties")
                .containsPattern("management\\.health\\.mail\\.enabled\\s*=\\s*false");

        for (Path file : filesToScan()) {
            if (file.equals(BASE_PROPERTIES) || !Files.exists(file)) {
                continue;
            }
            String normalized = normalizeWithoutComments(read(file));
            String flat = normalized.replaceAll("\\s+", "")
                    .replace("{", "").replace("}", "")
                    .replace("\"", "").replace("'", "");
            assertThat(flat)
                    .as("%s не должен переопределять management.health.mail "
                            + "(плоская, вложенная и flow/JSON-формы, env-варианты имени, "
                            + "продолжения строк и \\u-эскейпы) "
                            + "и не должен подменять конфиг через SPRING_APPLICATION_JSON/spring.config", file)
                    .doesNotContain("management.health.mail")
                    .doesNotContain("management:health:mail")
                    .doesNotContain("spring.application.json")
                    .doesNotContain("spring.config.");
        }
    }

    /**
     * Все профильные конфиги приложения рекурсивно (любой формат, включая
     * подкаталог {@code config/}) + compose-файлы + векторы доставки env.
     */
    private static List<Path> filesToScan() {
        List<Path> files = new ArrayList<>(List.of(
                Path.of("docker-compose.yml"),
                Path.of("docker-compose.staging.yml"),
                Path.of("docker-compose.override.yml"),
                Path.of("Dockerfile"),
                Path.of(".env.example"),
                Path.of(".github/workflows/deploy.yml")));
        try (Stream<Path> resources = Files.walk(RESOURCES_DIR)) {
            resources.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("application")
                                && (name.endsWith(".properties") || name.endsWith(".yml")
                                || name.endsWith(".yaml") || name.endsWith(".json"));
                    })
                    .filter(p -> !p.equals(BASE_PROPERTIES))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("нет доступа к " + RESOURCES_DIR, e);
        }
        return files;
    }

    /**
     * Схлопывает продолжения строк свойств, убирает #-комментарии, декодирует
     * {@code \\uXXXX} и приводит ключи к виду, который Spring резолвит из env.
     */
    private static String normalizeWithoutComments(String text) {
        String joined = text.replace("\r\n", "\n").replace("\\\n", "");
        StringBuilder out = new StringBuilder(joined.length());
        for (String line : joined.split("\n", -1)) {
            int commentAt = line.indexOf('#');
            out.append(commentAt >= 0 ? line.substring(0, commentAt) : line).append('\n');
        }
        return decodeUnicodeEscapes(out.toString()).toLowerCase().replace('_', '.').replace('-', '.');
    }

    /** Properties.load и YAML декодируют {@code \\uXXXX} до резолва ключа. */
    private static String decodeUnicodeEscapes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 6 <= text.length() && text.charAt(i + 1) == 'u'
                    && text.substring(i + 2, i + 6).matches("[0-9a-fA-F]{4}")) {
                out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
