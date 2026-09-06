package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitored_x_accounts")
class MonitoredXAccount {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  String accountId;

  @Column(nullable = false, unique = true)
  String handle;

  @Column(nullable = false)
  String displayName;

  @Column(nullable = false)
  String topics;

  @Column(nullable = false)
  double relevanceThreshold;

  @Column(nullable = false)
  boolean monitoringEnabled;

  @Column(nullable = false)
  Instant createdAt;

  Instant updatedAt;
  Instant removedAt;
  Instant lastSuccessfulSyncAt;
  Instant lastSyncAttemptAt;
  Instant rateLimitResetAt;
  Integer rateLimitLimit;
  Integer rateLimitRemaining;
  String lastPostId;
  String lastError;
  int consecutiveErrors;

  @Version long version;

  protected MonitoredXAccount() {}

  MonitoredXAccount(
      UUID id,
      String accountId,
      String handle,
      String displayName,
      String topics,
      double relevanceThreshold) {
    this.id = id;
    this.accountId = accountId;
    this.handle = handle;
    this.displayName = displayName;
    this.topics = topics;
    this.relevanceThreshold = relevanceThreshold;
    this.monitoringEnabled = true;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }
}
