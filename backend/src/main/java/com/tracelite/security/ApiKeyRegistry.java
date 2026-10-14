package com.tracelite.security;

import com.tracelite.security.ApiSecurityProperties.ProjectCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

public class ApiKeyRegistry {

    public static final String INGEST_EVENTS = "INGEST_EVENTS";
    public static final String MANAGE_ISSUES = "MANAGE_ISSUES";
    public static final String MANAGEMENT_CLIENT = "management";

    private static final Pattern API_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    private final List<RegisteredKey> registeredKeys;

    public ApiKeyRegistry(ApiSecurityProperties properties) {
        List<RegisteredKey> keys = new ArrayList<>();
        if (properties.managementKey() != null) {
            keys.add(register(
                    properties.managementKey(), "tracelite.security.management-key", MANAGEMENT_CLIENT, MANAGE_ISSUES));
        }
        for (int index = 0; index < properties.projects().size(); index++) {
            ProjectCredentials project = properties.projects().get(index);
            keys.add(register(
                    project.ingestionKey(),
                    "tracelite.security.projects[" + index + "].ingestion-key",
                    new IngestionClient(project.application()),
                    INGEST_EVENTS));
        }
        rejectDuplicateKeys(keys);
        this.registeredKeys = List.copyOf(keys);
    }

    public Optional<Authentication> authenticate(String presentedKey) {
        if (presentedKey == null || !API_KEY_FORMAT.matcher(presentedKey).matches()) {
            return Optional.empty();
        }
        byte[] presentedHash = sha256(presentedKey);
        RegisteredKey match = null;
        for (RegisteredKey key : registeredKeys) {
            if (MessageDigest.isEqual(key.hash(), presentedHash)) {
                match = key;
            }
        }
        return Optional.ofNullable(match).map(RegisteredKey::toAuthentication);
    }

    public boolean hasKeys() {
        return !registeredKeys.isEmpty();
    }

    private static RegisteredKey register(String key, String propertyName, Object principal, String authority) {
        if (key == null || !API_KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalStateException(propertyName + " must be 32 to 128 letters, digits, '_' or '-'");
        }
        return new RegisteredKey(sha256(key), principal, authority);
    }

    private static void rejectDuplicateKeys(List<RegisteredKey> keys) {
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (MessageDigest.isEqual(keys.get(i).hash(), keys.get(j).hash())) {
                    throw new IllegalStateException("Each configured API key must be different from every other key");
                }
            }
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record RegisteredKey(byte[] hash, Object principal, String authority) {

        Authentication toAuthentication() {
            return new PreAuthenticatedAuthenticationToken(
                    principal, null, AuthorityUtils.createAuthorityList(authority));
        }
    }
}
