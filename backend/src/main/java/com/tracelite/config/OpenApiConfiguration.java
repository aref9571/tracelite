package com.tracelite.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI traceLiteOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TraceLite API")
                .description("Error ingestion and issue investigation API")
                .version("v1")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
