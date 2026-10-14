package com.tracelite.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ApiSecurityPropertiesTests {

    private static final String VALID_KEY = "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(RegistryConfiguration.class);

    @Test
    void bindsKeysForSeveralApplicationsAndOrigins() {
        contextRunner
                .withPropertyValues(
                        "tracelite.security.management-key=" + VALID_KEY,
                        "tracelite.security.projects[0].application=checkout-service",
                        "tracelite.security.projects[0].ingestion-key=" + VALID_KEY.toUpperCase(),
                        "tracelite.security.projects[1].application=billing-service",
                        "tracelite.security.projects[1].ingestion-key=" + VALID_KEY.replace('0', '_'),
                        "tracelite.security.allowed-origins=http://localhost:5173,https://tracelite.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ApiSecurityProperties properties = context.getBean(ApiSecurityProperties.class);
                    assertThat(properties.projects()).extracting(ApiSecurityProperties.ProjectCredentials::application)
                            .containsExactly("checkout-service", "billing-service");
                    assertThat(properties.allowedOrigins())
                            .containsExactly("http://localhost:5173", "https://tracelite.example.com");
                });
    }

    @Test
    void startsWithoutAnyKeys() {
        contextRunner.withPropertyValues("tracelite.security.management-key=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyRegistry.class).hasKeys()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"too-short", "0123456789abcdef0123456789abcde!", "0123456789 abcdef0123456789abcdef"})
    void rejectsAMalformedManagementKey(String key) {
        contextRunner.withPropertyValues("tracelite.security.management-key=" + key).run(context -> {
            assertThat(context).hasFailed()
                    .getFailure().rootCause()
                    .hasMessage("tracelite.security.management-key must be 32 to 128 letters, digits, '_' or '-'");
            StringWriter stackTrace = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(stackTrace));
            assertThat(stackTrace.toString()).doesNotContain(key);
        });
    }

    @Test
    void rejectsAProjectWithoutAnIngestionKey() {
        contextRunner.withPropertyValues("tracelite.security.projects[0].application=checkout-service")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessage("tracelite.security.projects[0].ingestion-key must be 32 to 128 letters, digits, '_' or '-'"));
    }

    @Test
    void rejectsAProjectWithAnInvalidApplicationName() {
        contextRunner
                .withPropertyValues(
                        "tracelite.security.projects[0].application=checkout service",
                        "tracelite.security.projects[0].ingestion-key=" + VALID_KEY)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("application"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "http://localhost:5173/", "https://*.example.com", "localhost:5173"})
    void rejectsWildcardsAndValuesThatAreNotOrigins(String origin) {
        contextRunner.withPropertyValues("tracelite.security.allowed-origins=" + origin)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("allowedOrigins"));
    }

    @Test
    void doesNotIncludeTheKeysInTheStringForm() {
        contextRunner
                .withPropertyValues(
                        "tracelite.security.management-key=" + VALID_KEY,
                        "tracelite.security.projects[0].application=checkout-service",
                        "tracelite.security.projects[0].ingestion-key=" + VALID_KEY.toUpperCase())
                .run(context -> assertThat(context.getBean(ApiSecurityProperties.class).toString())
                        .contains("checkout-service", "[REDACTED]")
                        .doesNotContainIgnoringCase(VALID_KEY));
    }

    @Test
    void failsWhenTwoConfiguredKeysAreEqual() {
        contextRunner
                .withPropertyValues(
                        "tracelite.security.management-key=" + VALID_KEY,
                        "tracelite.security.projects[0].application=checkout-service",
                        "tracelite.security.projects[0].ingestion-key=" + VALID_KEY)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessage("Each configured API key must be different from every other key"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiSecurityProperties.class)
    static class RegistryConfiguration {

        @Bean
        ApiKeyRegistry apiKeyRegistry(ApiSecurityProperties properties) {
            return new ApiKeyRegistry(properties);
        }
    }
}
