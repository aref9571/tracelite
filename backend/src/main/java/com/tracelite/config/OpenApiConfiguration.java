package com.tracelite.config;

import com.tracelite.security.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI traceLiteOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TraceLite API")
                        .description("Error ingestion and issue investigation API")
                        .version("v1")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthenticationFilter.API_KEY_HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
