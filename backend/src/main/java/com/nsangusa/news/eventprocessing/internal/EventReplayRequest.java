package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_replay_requests")
class EventReplayRequest {
  @Id UUID id;

  @Column(nullable = false)
  UUID actorId;

  @Column(nullable = false, length = 500)
  String reason;

  boolean dryRun;
  boolean includePoison;
  int messagesPerSecond;
  int candidateCount;
  int replayedCount;
  int blockedCount;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  Instant requestedAt;

  Instant completedAt;
  Instant failedFrom;
  Instant failedTo;

  @Column(nullable = false, columnDefinition = "text")
  String auditMetadata;

  protected EventReplayRequest() {}

  EventReplayRequest(
      UUID actorId,
      String reason,
      boolean dryRun,
      boolean includePoison,
      int messagesPerSecond,
      int candidateCount,
      Instant failedFrom,
      Instant failedTo,
      String auditMetadata) {
    this.id = UUID.randomUUID();
    this.actorId = actorId;
    this.reason = reason;
    this.dryRun = dryRun;
    this.includePoison = includePoison;
    this.messagesPerSecond = messagesPerSecond;
    this.candidateCount = candidateCount;
    this.failedFrom = failedFrom;
    this.failedTo = failedTo;
    this.auditMetadata = auditMetadata;
    this.status = dryRun ? "dry_run_complete" : "pending";
    this.requestedAt = Instant.now();
    this.completedAt = dryRun ? this.requestedAt : null;
  }
}
