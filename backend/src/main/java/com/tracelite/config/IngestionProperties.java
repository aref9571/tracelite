package com.tracelite.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tracelite.ingestion")
public record IngestionProperties(
        @NotNull DataSize maxRequestSize,
        @NotNull Duration maxClockSkew,
        @NotNull Duration maxEventAge) {
}
