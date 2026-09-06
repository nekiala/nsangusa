package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "event_replay_items")
class EventReplayItem {
  @Id UUID id;

  @Column(nullable = false)
  UUID replayRequestId;

  @Column(nullable = false)
  UUID failedEventId;

  @Column(nullable = false)
  String status;

  protected EventReplayItem() {}

  EventReplayItem(UUID replayRequestId, UUID failedEventId, String status) {
    this.id = UUID.randomUUID();
    this.replayRequestId = replayRequestId;
    this.failedEventId = failedEventId;
    this.status = status;
  }
}
