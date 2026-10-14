package com.tracelite.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.ApiSecurityProperties.ProjectCredentials;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigurationTest {

    private static final String NO_KEYS_WARNING = "No API keys are configured";

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void warnsWhenNoApiKeysAreConfigured(CapturedOutput output) {
        ApiKeyRegistry registry = configuration.apiKeyRegistry(new ApiSecurityProperties(null, null, null));

        assertThat(registry.hasKeys()).isFalse();
        assertThat(output).contains(NO_KEYS_WARNING);
    }

    @Test
    void doesNotWarnWhenAnIngestionKeyIsConfigured(CapturedOutput output) {
        ApiKeyRegistry registry = configuration.apiKeyRegistry(new ApiSecurityProperties(null,
                List.of(new ProjectCredentials("checkout-service", TestApiKeys.CHECKOUT_INGESTION_KEY)), null));

        assertThat(registry.hasKeys()).isTrue();
        assertThat(output).doesNotContain(NO_KEYS_WARNING);
    }
}
