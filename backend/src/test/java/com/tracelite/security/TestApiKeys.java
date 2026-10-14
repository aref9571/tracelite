package com.tracelite.security;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

public final class TestApiKeys {

    public static final String HEADER = ApiKeyAuthenticationFilter.API_KEY_HEADER;
    public static final String MANAGEMENT_KEY = "test-management-key-0123456789abcdef";
    public static final String CHECKOUT_INGESTION_KEY = "test-checkout-ingestion-key-0123456789";
    public static final String BILLING_INGESTION_KEY = "test-billing-ingestion-key-0123456789ab";

    private TestApiKeys() {
    }

    public static HttpEntity<Void> managementRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER, MANAGEMENT_KEY);
        return new HttpEntity<>(headers);
    }
}
