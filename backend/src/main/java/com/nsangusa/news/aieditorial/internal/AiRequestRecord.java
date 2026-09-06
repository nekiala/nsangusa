package com.nsangusa.news.aieditorial.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_requests")
class AiRequestRecord {
  @Id UUID id;

  @Column(nullable = false)
  UUID storyCandidateId;

  @Column(nullable = false)
  String operation;

  @Column(nullable = false)
  String provider;

  @Column(nullable = false)
  String model;

  @Column(nullable = false)
  String promptVersion;

  @Column(nullable = false)
  Instant createdAt;

  Instant completedAt;
  long inputTokens;
  long outputTokens;
  String status;
  String errorCode;

  protected AiRequestRecord() {}

  AiRequestRecord(UUID storyCandidateId, String operation) {
    this.id = UUID.randomUUID();
    this.storyCandidateId = storyCandidateId;
    this.operation = operation;
    this.provider = "configured";
    this.model = "configured";
    this.promptVersion = "editorial-v1";
    this.createdAt = Instant.now();
    this.status = "started";
  }

  void complete(String provider, String model, long inputTokens, long outputTokens) {
    this.provider = provider;
    this.model = model;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.status = "completed";
    this.completedAt = Instant.now();
  }
}
