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
  UUID eventId;
  long reservedTokens;
  Long configurationVersion;
  String requestedModel;

  @Column(columnDefinition = "text")
  String promptGuidance;

  String secretReference;

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

  AiRequestRecord(
      UUID storyCandidateId,
      UUID eventId,
      String operation,
      com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration configuration) {
    this(storyCandidateId, operation);
    this.eventId = eventId;
    this.provider = configuration.provider();
    this.model = configuration.model();
    this.promptVersion = configuration.promptVersion();
    this.configurationVersion = configuration.configurationVersion();
    this.requestedModel = configuration.model();
    this.promptGuidance = configuration.guidance();
    this.secretReference = configuration.secretReference();
    this.status = "pending";
  }

  com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration configuration() {
    return new com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration(
        provider,
        requestedModel,
        promptVersion,
        configurationVersion,
        promptGuidance,
        secretReference);
  }

  void complete(String provider, String model, long inputTokens, long outputTokens) {
    this.provider = provider;
    this.model = model;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.status = "completed";
    this.completedAt = Instant.now();
  }

  void block(String errorCode) {
    this.status = "blocked";
    this.errorCode = errorCode;
    this.completedAt = Instant.now();
  }

  void fail(String errorCode) {
    this.status = "failed";
    this.errorCode = errorCode;
    this.completedAt = Instant.now();
  }
}
