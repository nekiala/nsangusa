package com.nsangusa.news.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope<T>(
    @NotNull UUID eventId,
    @NotBlank String eventType,
    int schemaVersion,
    @NotNull UUID aggregateId,
    @NotNull UUID correlationId,
    UUID causationId,
    @NotNull Instant timestamp,
    @NotBlank String producer,
    @NotNull Map<String, String> traceContext,
    @NotBlank String idempotencyKey,
    @NotNull @Valid T payload) {}
