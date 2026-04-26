package ru.mngerasimenko.todolist.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Глобальная сериализация LocalDateTime как ISO с зоной +00:00.
 * <p>
 * Контейнер todo-app и postgres-db работают в UTC, поэтому LocalDateTime в Entity
 * семантически содержит UTC-момент. Без явной TZ в JSON клиенты не могли понять,
 * что время серверное и должны конвертировать в свой часовой пояс.
 * <p>
 * Сериализация: LocalDateTime → "2026-04-26T06:26:06.088239+00:00".
 * Десериализация: принимаем оба формата (со смещением и без) для обратной совместимости
 * с уже захардкоженными в БД значениями и запросами от старых клиентов.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter SERIALIZE_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeAsUtcCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                    @Override
                    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                        gen.writeString(value.atOffset(ZoneOffset.UTC).format(SERIALIZE_FORMATTER));
                    }
                })
                .deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                    @Override
                    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                        String text = p.getText();
                        if (text == null || text.isBlank()) {
                            return null;
                        }
                        // Сначала пробуем со смещением (новые клиенты или внутренние вызовы).
                        try {
                            return OffsetDateTime.parse(text)
                                    .withOffsetSameInstant(ZoneOffset.UTC)
                                    .toLocalDateTime();
                        } catch (DateTimeParseException ignore) {
                            // Без смещения — трактуем как уже UTC (legacy-формат).
                            return LocalDateTime.parse(text);
                        }
                    }
                });
    }
}
