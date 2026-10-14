package com.tracelite.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tracelite.security.ApiSecurityProperties.ProjectCredentials;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

class ApiKeyRegistryTest {

    private static final String MANAGEMENT_KEY = "management-key-0123456789abcdef0123";
    private static final String CHECKOUT_KEY = "checkout-key-0123456789abcdef0123456";
    private static final String CHECKOUT_ROTATED_KEY = "checkout-rotated-0123456789abcdef0123";
    private static final String BILLING_KEY = "billing-key-0123456789abcdef01234567";

    private final ApiKeyRegistry registry = new ApiKeyRegistry(new ApiSecurityProperties(
            MANAGEMENT_KEY,
            List.of(
                    new ProjectCredentials("checkout-service", CHECKOUT_KEY),
                    new ProjectCredentials("checkout-service", CHECKOUT_ROTATED_KEY),
                    new ProjectCredentials("billing-service", BILLING_KEY)),
            List.of()));

    @Test
    void authenticatesTheManagementKeyWithTheManagementAuthority() {
        Authentication authentication = registry.authenticate(MANAGEMENT_KEY).orElseThrow();

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(ApiKeyRegistry.MANAGEMENT_CLIENT);
        assertThat(AuthorityUtils.authorityListToSet(authentication.getAuthorities()))
                .containsExactly(ApiKeyRegistry.MANAGE_ISSUES);
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    void authenticatesAnIngestionKeyAsItsApplication() {
        Authentication authentication = registry.authenticate(BILLING_KEY).orElseThrow();

        assertThat(authentication.getPrincipal()).isEqualTo(new IngestionClient("billing-service"));
        assertThat(AuthorityUtils.authorityListToSet(authentication.getAuthorities()))
                .containsExactly(ApiKeyRegistry.INGEST_EVENTS);
    }

    @Test
    void acceptsEveryKeyConfiguredForAnApplicationSoKeysCanBeRotated() {
        assertThat(registry.authenticate(CHECKOUT_KEY).orElseThrow().getPrincipal())
                .isEqualTo(new IngestionClient("checkout-service"));
        assertThat(registry.authenticate(CHECKOUT_ROTATED_KEY).orElseThrow().getPrincipal())
                .isEqualTo(new IngestionClient("checkout-service"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "unknown-key-0123456789abcdef0123456789",
            "management-key-0123456789abcdef012",
            "MANAGEMENT-KEY-0123456789ABCDEF0123",
            "management-key-0123456789abcdef0123 ",
            "short"})
    void rejectsMissingMalformedAndUnknownKeys(String presentedKey) {
        assertThat(registry.authenticate(presentedKey)).isEmpty();
    }

    @Test
    void rejectsConfigurationsThatReuseAKey() {
        ApiSecurityProperties properties = new ApiSecurityProperties(
                CHECKOUT_KEY, List.of(new ProjectCredentials("checkout-service", CHECKOUT_KEY)), List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> new ApiKeyRegistry(properties))
                .withMessage("Each configured API key must be different from every other key")
                .withMessageNotContaining(CHECKOUT_KEY);
    }

    @Test
    void rejectsAMalformedConfiguredKeyWithoutRevealingIt() {
        String malformedKey = "checkout key with spaces 0123456789";
        ApiSecurityProperties properties = new ApiSecurityProperties(
                null, List.of(new ProjectCredentials("checkout-service", malformedKey)), List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> new ApiKeyRegistry(properties))
                .withMessage("tracelite.security.projects[0].ingestion-key must be 32 to 128 letters, digits, '_' or '-'");
    }

    @Test
    void acceptsNothingWhenNoKeysAreConfigured() {
        ApiKeyRegistry empty = new ApiKeyRegistry(new ApiSecurityProperties(null, null, null));

        assertThat(empty.hasKeys()).isFalse();
        assertThat(empty.authenticate(MANAGEMENT_KEY)).isEmpty();
    }

    @Test
    void treatsABlankManagementKeyAsNotConfigured() {
        ApiKeyRegistry registryWithBlankKey = new ApiKeyRegistry(new ApiSecurityProperties(" ", null, null));

        assertThat(registryWithBlankKey.hasKeys()).isFalse();
    }
}
