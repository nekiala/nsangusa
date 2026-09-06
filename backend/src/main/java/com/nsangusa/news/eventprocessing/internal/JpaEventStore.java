package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaEventStore implements DurableEventPublisher, ProcessedEventRegistry {
  private final OutboxRepository outbox;
  private final ProcessedEventRepository processed;
  private final ObjectMapper objectMapper;

  JpaEventStore(
      OutboxRepository outbox, ProcessedEventRepository processed, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.processed = processed;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public UUID enqueue(
      String eventType,
      UUID aggregateId,
      UUID correlationId,
      UUID causationId,
      String idempotencyKey,
      Object payload) {
    UUID eventId = UUID.randomUUID();
    var traceContext =
        MDC.get("traceId") == null
            ? Map.<String, String>of()
            : Map.of("traceId", MDC.get("traceId"));
    var envelope =
        new EventEnvelope<>(
            eventId,
            eventType,
            1,
            aggregateId,
            correlationId,
            causationId,
            Instant.now(),
            "news-platform",
            traceContext,
            idempotencyKey,
            payload);
    try {
      outbox.save(
          new OutboxEvent(
              eventId,
              eventType,
              aggregateId,
              correlationId,
              causationId,
              idempotencyKey,
              objectMapper.writeValueAsString(envelope),
              envelope.timestamp()));
      return eventId;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Event payload is not serializable", exception);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean wasProcessed(UUID eventId, String consumer) {
    return processed.existsByEventIdAndConsumerName(eventId, consumer);
  }

  @Override
  public void markProcessed(UUID eventId, String consumer) {
    processed.save(new ProcessedEvent(eventId, consumer));
  }
}
