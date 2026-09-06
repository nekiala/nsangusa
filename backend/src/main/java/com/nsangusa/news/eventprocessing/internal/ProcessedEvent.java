package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
class ProcessedEvent {
  @Id UUID eventId;
  @Id String consumerName;

  @Column(nullable = false)
  Instant processedAt;

  protected ProcessedEvent() {}

  ProcessedEvent(UUID eventId, String consumerName) {
    this.eventId = eventId;
    this.consumerName = consumerName;
    this.processedAt = Instant.now();
  }

  static final class Key implements Serializable {
    UUID eventId;
    String consumerName;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(eventId, key.eventId)
          && Objects.equals(consumerName, key.consumerName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(eventId, consumerName);
    }
  }
}
