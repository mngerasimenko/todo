package ru.mngerasimenko.todolist.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Плашка «доступно обновление» берёт число из env джобы {@code deploy-production}.
 * В {@code .env} оно попадает только ссылкой на эту переменную, без второй цифры.
 * Сам номер релиза тест не запоминает: следующий бамп правит одну строку в workflow.
 */
class ProductionDeployVersionTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/deploy.yml");

    @Test
    @DisplayName("Прод-деплой пишет latest и minimum в .env ссылкой, не второй цифрой")
    void productionDeployWritesVersionsByReference() throws IOException {
        String job = jobBody(Files.readString(WORKFLOW).replace("\r\n", "\n"), "deploy-production");

        assertThat(envAssignment(job, "LATEST_ANDROID_VERSION")).matches("\\d+");
        assertThat(envAssignment(job, "MIN_ANDROID_VERSION")).matches("\\d+");
        assertThat(writtenEnvValue(job, "LATEST_ANDROID_VERSION"))
                .isEqualTo("${{ env.LATEST_ANDROID_VERSION }}");
        assertThat(writtenEnvValue(job, "MIN_ANDROID_VERSION"))
                .isEqualTo("${{ env.MIN_ANDROID_VERSION }}");
    }

    @Test
    @DisplayName("Число берётся из джобы deploy-production, а не из следующей джобы")
    void assignmentIsReadFromTheProductionJob() {
        String yaml = """
                jobs:
                  deploy-production:
                    env:
                      LATEST_ANDROID_VERSION: 40
                      MIN_ANDROID_VERSION: 3
                  later-job:
                    env:
                      LATEST_ANDROID_VERSION: 1
                      MIN_ANDROID_VERSION: 1
                """;

        String job = jobBody(yaml, "deploy-production");

        assertThat(envAssignment(job, "LATEST_ANDROID_VERSION")).isEqualTo("40");
        assertThat(envAssignment(job, "MIN_ANDROID_VERSION")).isEqualTo("3");
    }

    private static String jobBody(String yaml, String jobName) {
        String header = "\n  " + jobName + ":";
        int headerAt = yaml.startsWith("  " + jobName + ":") ? 0 : yaml.indexOf(header);
        if (headerAt < 0) {
            throw new IllegalStateException("job not found: " + jobName);
        }
        int lineAfterHeader = yaml.indexOf('\n', headerAt + 1);
        int nextJob = lineAfterHeader < 0 ? -1 : nextTopLevelKey(yaml, lineAfterHeader + 1);
        if (nextJob < 0) {
            return yaml.substring(headerAt);
        }
        return yaml.substring(headerAt, nextJob);
    }

    private static int nextTopLevelKey(String yaml, int from) {
        int lineStart = from;
        while (lineStart < yaml.length()) {
            int lineEnd = yaml.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = yaml.length();
            }
            String line = yaml.substring(lineStart, lineEnd);
            if (line.startsWith("  ") && !line.startsWith("   ") && line.indexOf(':') > 2) {
                return lineStart;
            }
            if (lineEnd == yaml.length()) {
                return -1;
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    private static String envAssignment(String jobBody, String key) {
        Pattern assignment = Pattern.compile("(?m)^[ \\t]+" + Pattern.quote(key) + ":[ \\t]*(\\d+)[ \\t]*$");
        Matcher matcher = assignment.matcher(jobBody);
        if (!matcher.find()) {
            throw new IllegalStateException("no env assignment: " + key);
        }
        String value = matcher.group(1);
        if (matcher.find()) {
            throw new IllegalStateException("more than one env assignment: " + key);
        }
        return value;
    }

    private static String writtenEnvValue(String jobBody, String key) {
        Matcher matcher = Pattern.compile("(?m)^[ \\t]+" + Pattern.quote(key) + "=(.*)$").matcher(jobBody);
        if (!matcher.find()) {
            throw new IllegalStateException("no .env write: " + key);
        }
        String value = matcher.group(1).trim();
        if (matcher.find()) {
            throw new IllegalStateException("more than one .env write: " + key);
        }
        return value;
    }
}
