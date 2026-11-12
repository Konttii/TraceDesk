package com.tracedesk.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "basicAuth";

    @Bean
    public OpenAPI traceDeskOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TraceDesk Support API")
                        .version("1.0.0")
                        .description("""
                                Сервис обращений в техподдержку (Стажёр 3).
                                
                                **Демо-аккаунты (пароль по умолчанию: `password`):**
                                - `irina` — Следователь Ирина (`INVESTIGATOR`)
                                - `pavel` — Следователь Павел (`INVESTIGATOR`)
                                - `oleg` — Оператор Олег (`SUPPORT`)
                                - `anna` — Оператор Анна (`SUPPORT`, второй оператор для проверки конкуренции)
                                """))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("HTTP Basic Auth (логин: irina / pavel / oleg / anna, пароль: password)")));
    }
}
