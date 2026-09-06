package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "failed_events")
class FailedEvent {
  @Id UUID id;

  UUID eventId;
  String eventType;
  UUID aggregateId;

  @Column(nullable = false)
  String dltTopic;

  @Column(nullable = false)
  int dltPartition;

  @Column(nullable = false)
  long dltOffset;

  @Column(nullable = false)
  String originalTopic;

  @Column(nullable = false)
  int originalPartition;

  @Column(nullable = false)
  long originalOffset;

  String consumerGroup;

  @Column(nullable = false, columnDefinition = "text")
  String payload;

  String exceptionClass;

  @Column(columnDefinition = "text")
  String exceptionMessage;

  int deliveryAttempt;
  boolean poisonMessage;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  Instant failedAt;

  @Column(nullable = false)
  Instant lastUpdatedAt;

  protected FailedEvent() {}

  FailedEvent(
      UUID eventId,
      String eventType,
      UUID aggregateId,
      String dltTopic,
      int dltPartition,
      long dltOffset,
      String originalTopic,
      int originalPartition,
      long originalOffset,
      String consumerGroup,
      String payload,
      String exceptionClass,
      String exceptionMessage,
      int deliveryAttempt,
      boolean poisonMessage) {
    this.id = UUID.randomUUID();
    this.eventId = eventId;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
    this.dltTopic = dltTopic;
    this.dltPartition = dltPartition;
    this.dltOffset = dltOffset;
    this.originalTopic = originalTopic;
    this.originalPartition = originalPartition;
    this.originalOffset = originalOffset;
    this.consumerGroup = consumerGroup;
    this.payload = payload;
    this.exceptionClass = exceptionClass;
    this.exceptionMessage = exceptionMessage;
    this.deliveryAttempt = deliveryAttempt;
    this.poisonMessage = poisonMessage;
    this.status = poisonMessage ? "poison" : "eligible";
    this.failedAt = Instant.now();
    this.lastUpdatedAt = this.failedAt;
  }
}
