package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_replay_suppressions")
class ReplaySuppression {
  @Id UUID aggregateId;

  @Column(nullable = false, length = 500)
  String reason;

  UUID actorId;

  @Column(nullable = false)
  Instant suppressedAt;

  protected ReplaySuppression() {}

  ReplaySuppression(UUID aggregateId, String reason, UUID actorId) {
    this.aggregateId = aggregateId;
    this.reason = reason;
    this.actorId = actorId;
    this.suppressedAt = Instant.now();
  }
}
