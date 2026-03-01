package ru.mngerasimenko.todolist.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.mngerasimenko.todolist.settings.AppProperties;

/**
 * Конфигурация OpenAPI / Swagger UI.
 * Добавляет описание API и кнопку "Authorize" для JWT-токена.
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final AppProperties appProperties;

    @Bean
    public OpenAPI openAPI() {
        String schemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Todo List API")
                        .description("REST API для управления списками задач")
                        .version(appProperties.getVersion()))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new Components()
                        .addSecuritySchemes(schemeName, new SecurityScheme()
                                .name(schemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
