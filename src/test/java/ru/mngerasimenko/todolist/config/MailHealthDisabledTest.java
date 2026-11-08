package ru.mngerasimenko.todolist.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.mail.MailHealthIndicator;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
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
 * properties/yml/yaml/json под {@code src/main/resources} — не только
 * {@code application*}: файл, подтягиваемый через spring.config.name/import
 * (включая подкаталог {@code config/}), обязан быть виден скану, — плюс
 * docker-compose-файлы, Dockerfile, .env.example и deploy.yml. Base
 * проверяется отдельно: паттерн флага там разрешён, но spring.*-подмены
 * (SPRING_APPLICATION_JSON/spring.config) — нет. Текст нормализуется (регистр,
 * {@code _}/{@code -} → точка, сплющенная форма вложенных YAML/JSON-ключей,
 * склейка {@code \<EOL>}-продолжений строк свойств, декодирование
 * {@code \\uXXXX}, снятие properties-эскейпов {@code \.}, срез #-комментариев
 * только вне кавычек и !-комментариев только в начале строки) — иначе Spring
 * резолвит management_health_mail_enabled, management:/health:/mail:,
 * flow-форму, escape-написание и строки с литеральным # в кавычках так,
 * что guard их не видит. Граница:
 * всё, что вне git-файлов — живой {@code .env}, host-local compose/override,
 * shell-env оператора (включая pass-through {@code JAVA_TOOL_OPTIONS} в
 * compose), значения GitHub vars/secrets — файлами репо не видны.</p>
 *
 * <p>Третий тест — второй эшелон против текстового сканера: реальный
 * production-резолв через {@code ConfigDataEnvironmentPostProcessor} поверх
 * classpath-ресурсов (см. {@link #productionProfileResolvesMailFlagDisabled()}).</p>
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
            if (!Files.exists(file)) {
                continue;
            }
            String flat = normalizeWithoutComments(read(file))
                    .replaceAll("\\s+", "")
                    .replace("{", "").replace("}", "")
                    .replace("\"", "").replace("'", "");
            if (file.equals(BASE_PROPERTIES)) {
                // Base легально содержит сам паттерн флага — проверяем её только
                // на подмену конфига целиком (иначе spring.application.json в base
                // обходил бы скан, не будучи проверенным нигде).
                assertThat(flat)
                        .as("base application.properties не должен подменять конфиг через "
                                + "SPRING_APPLICATION_JSON/spring.config (паттерн флага здесь разрешён)")
                        .doesNotContain("spring.application.json")
                        .doesNotContain("spring.config.");
                continue;
            }
            assertThat(flat)
                    .as("%s не должен переопределять management.health.mail "
                            + "(плоская, вложенная и flow/JSON-формы, env-варианты имени, "
                            + "продолжения строк, \\u- и properties-эскейпы) "
                            + "и не должен подменять конфиг через SPRING_APPLICATION_JSON/spring.config", file)
                    .doesNotContain("management.health.mail")
                    .doesNotContain("management:health:mail")
                    .doesNotContain("spring.application.json")
                    .doesNotContain("spring.config.");
        }
    }

    /**
     * Второй эшелон: текстовый сканер не видит, как флаг резолвит сама Spring.
     * {@code ConfigDataEnvironmentPostProcessor.applyTo} поверх реальных
     * classpath-ресурсов с профилем production: production-конфиг должен оставить
     * флаг выключенным, иначе override из application-production.properties
     * (в т.ч. properties-эскейп вида {@code management\.health\.mail\.enabled})
     * включит индикатор в проде, мимо всех тестов под test-профилем.
     * Позитивный контроль — springdoc-ключ, которого нет в base: без загруженного
     * production-файла проверка флага прошла бы вакуумно.
     */
    @Test
    void productionProfileResolvesMailFlagDisabled() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), null, "production");
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled"))
                .as("позитивный контроль: production-профиль применён, "
                        + "application-production.properties загружен в резолв")
                .isEqualTo("false");
        assertThat(environment.getProperty("management.health.mail.enabled"))
                .as("production-резолв флага отключения mail health")
                .isEqualTo("false");
    }

    /**
     * Все конфиги-кандидаты под resources рекурсивно (все properties/yml/yaml/json,
     * не только {@code application*} — файл, подтягиваемый через
     * spring.config.name/import, обязан быть виден скану) + compose-файлы
     * + векторы доставки env. Base в список попадает, но проверяется отдельно
     * (только spring.*) — см. {@link #flagLivesOnlyInBasePropertiesAndIsNotOverridden()}.
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
                        return name.endsWith(".properties") || name.endsWith(".yml")
                                || name.endsWith(".yaml") || name.endsWith(".json");
                    })
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("нет доступа к " + RESOURCES_DIR, e);
        }
        return files;
    }

    /**
     * Схлопывает продолжения строк свойств, убирает #-комментарии (только вне
     * кавычек) и !-комментарии (только в начале строки), декодирует
     * {@code \\uXXXX}, снимает properties-эскейпы {@code \.} и приводит ключи
     * к виду, который Spring резолвит из env.
     */
    private static String normalizeWithoutComments(String text) {
        String joined = text.replace("\r\n", "\n").replace("\\\n", "");
        StringBuilder out = new StringBuilder(joined.length());
        for (String line : joined.split("\n", -1)) {
            out.append(stripComment(line)).append('\n');
        }
        // Properties.load снимает properties-эскейпы (\., \:, \\) до резолва ключа —
        // без этого management\.health\.mail\.enabled уходит из-под проверок подстрок.
        return decodeUnicodeEscapes(out.toString()).replace("\\", "")
                .toLowerCase().replace('_', '.').replace('-', '.');
    }

    /**
     * Срезает #-комментарий только вне кавычек: внутри {@code "…"} / {@code '…'}
     * литеральный {@code #} — не комментарий (compose flow-строки вида
     * {@code {"NOTE": "a # b", …}}), а срез по первому {@code #} прятал бы хвост
     * строки вместе с override. {@code !} — комментарий только в начале строки
     * (первый не-пробельный символ, семантика Properties); середина строки
     * литеральна ({@code $${!}} в compose entrypoint).
     */
    private static String stripComment(String line) {
        int firstNonWs = 0;
        while (firstNonWs < line.length() && Character.isWhitespace(line.charAt(firstNonWs))) {
            firstNonWs++;
        }
        if (firstNonWs < line.length() && line.charAt(firstNonWs) == '!') {
            return line.substring(0, firstNonWs);
        }
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && (inDouble || inSingle)) {
                i++; // экранированная кавычка внутри строки (YAML double-quoted)
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '#' && !inDouble && !inSingle) {
                return line.substring(0, i);
            }
        }
        return line;
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
