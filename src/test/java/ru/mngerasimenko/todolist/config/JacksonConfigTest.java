package ru.mngerasimenko.todolist.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что глобальный сериалайзер {@link JacksonConfig} превращает {@link LocalDateTime}
 * в ISO-строку со смещением +00:00 и принимает обратно оба формата.
 */
class JacksonConfigTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(ctx -> mapper = ctx.getBean(ObjectMapper.class));
    }

    @Test
    void serializesLocalDateTimeWithUtcOffset() throws Exception {
        // ISO_OFFSET_DATE_TIME для UTC использует компактный 'Z' вместо '+00:00'
        LocalDateTime ts = LocalDateTime.of(2026, 4, 26, 6, 26, 6);
        String json = mapper.writeValueAsString(ts);
        assertThat(json).isEqualTo("\"2026-04-26T06:26:06Z\"");
    }

    @Test
    void serializesLocalDateTimeWithFractionalSeconds() throws Exception {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 26, 6, 26, 6, 123_000_000);
        String json = mapper.writeValueAsString(ts);
        assertThat(json).isEqualTo("\"2026-04-26T06:26:06.123Z\"");
    }

    @Test
    void deserializesIsoWithoutOffsetAsLegacy() throws Exception {
        LocalDateTime parsed = mapper.readValue("\"2026-04-26T06:26:06\"", LocalDateTime.class);
        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 4, 26, 6, 26, 6));
    }

    @Test
    void deserializesIsoWithUtcOffset() throws Exception {
        LocalDateTime parsed = mapper.readValue("\"2026-04-26T06:26:06+00:00\"", LocalDateTime.class);
        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 4, 26, 6, 26, 6));
    }

    @Test
    void deserializesIsoWithMskOffsetNormalizedToUtc() throws Exception {
        // 09:26+03:00 = 06:26 UTC
        LocalDateTime parsed = mapper.readValue("\"2026-04-26T09:26:06+03:00\"", LocalDateTime.class);
        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 4, 26, 6, 26, 6));
    }

    @Test
    void deserializesIsoWithNegativeOffsetNormalizedToUtc() throws Exception {
        // 01:26-05:00 = 06:26 UTC — симметрия с MSK, проверяет нормализацию в обе стороны
        LocalDateTime parsed = mapper.readValue("\"2026-04-26T01:26:06-05:00\"", LocalDateTime.class);
        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 4, 26, 6, 26, 6));
    }

    @Test
    void roundTripPreservesValue() throws Exception {
        LocalDateTime original = LocalDateTime.of(2026, 4, 26, 6, 26, 6);
        String json = mapper.writeValueAsString(original);
        LocalDateTime back = mapper.readValue(json, LocalDateTime.class);
        assertThat(back).isEqualTo(original);
    }
}
