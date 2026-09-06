package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
class OutboxEvent {
  @Id UUID id;

  @Column(nullable = false)
  String eventType;

  @Column(nullable = false)
  UUID aggregateId;

  @Column(nullable = false)
  UUID correlationId;

  UUID causationId;

  @Column(nullable = false, unique = true)
  String idempotencyKey;

  @Column(nullable = false, columnDefinition = "text")
  String envelopeJson;

  @Column(nullable = false)
  Instant createdAt;

  Instant publishedAt;

  @Version long version;

  protected OutboxEvent() {}

  OutboxEvent(
      UUID id,
      String eventType,
      UUID aggregateId,
      UUID correlationId,
      UUID causationId,
      String idempotencyKey,
      String envelopeJson,
      Instant createdAt) {
    this.id = id;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.idempotencyKey = idempotencyKey;
    this.envelopeJson = envelopeJson;
    this.createdAt = createdAt;
  }

  void published(Instant at) {
    this.publishedAt = at;
  }
}
