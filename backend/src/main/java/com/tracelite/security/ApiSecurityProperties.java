package com.tracelite.security;

import com.tracelite.common.web.IdentifierRules;
import com.tracelite.issue.domain.Issue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tracelite.security")
public record ApiSecurityProperties(
        String managementKey,

        List<@Valid @NotNull ProjectCredentials> projects,

        List<@NotNull @Pattern(regexp = ORIGIN_PATTERN, message = ORIGIN_MESSAGE) String> allowedOrigins) {

    private static final String ORIGIN_PATTERN = "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?";
    private static final String ORIGIN_MESSAGE = "must be an origin such as http://localhost:5173, without a path or wildcard";

    public ApiSecurityProperties {
        managementKey = managementKey == null || managementKey.isBlank() ? null : managementKey;
        projects = projects == null ? List.of() : projects;
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }

    @Override
    public String toString() {
        return "ApiSecurityProperties[managementKey=" + redact(managementKey) + ", projects=" + projects
                + ", allowedOrigins=" + allowedOrigins + "]";
    }

    private static String redact(String key) {
        return key == null ? "null" : "[REDACTED]";
    }

    public record ProjectCredentials(
            @NotBlank
            @Size(max = Issue.MAX_APPLICATION_LENGTH)
            @Pattern(regexp = IdentifierRules.PATTERN, message = IdentifierRules.MESSAGE)
            String application,

            String ingestionKey) {

        @Override
        public String toString() {
            return "ProjectCredentials[application=" + application + ", ingestionKey=" + redact(ingestionKey) + "]";
        }
    }
}
