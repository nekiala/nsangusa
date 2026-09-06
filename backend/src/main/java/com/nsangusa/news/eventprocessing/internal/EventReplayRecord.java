package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_replay_records")
class EventReplayRecord {
  @Id UUID id;

  @Column(nullable = false)
  UUID replayRequestId;

  @Column(nullable = false)
  UUID failedEventId;

  UUID eventId;
  UUID aggregateId;

  @Column(nullable = false)
  String originalTopic;

  @Column(nullable = false)
  String outcome;

  @Column(length = 2000)
  String detail;

  @Column(nullable = false)
  Instant occurredAt;

  protected EventReplayRecord() {}

  EventReplayRecord(EventReplayRequest request, FailedEvent event, String outcome, String detail) {
    this.id = UUID.randomUUID();
    this.replayRequestId = request.id;
    this.failedEventId = event.id;
    this.eventId = event.eventId;
    this.aggregateId = event.aggregateId;
    this.originalTopic = event.originalTopic;
    this.outcome = outcome;
    this.detail = detail;
    this.occurredAt = Instant.now();
  }
}
