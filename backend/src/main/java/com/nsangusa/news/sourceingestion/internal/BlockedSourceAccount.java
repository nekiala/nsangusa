package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "blocked_source_accounts")
class BlockedSourceAccount {
  @Id String accountId;

  @Column(nullable = false, length = 500)
  String reason;

  @Column(nullable = false)
  Instant createdAt;

  UUID actorId;

  protected BlockedSourceAccount() {}

  BlockedSourceAccount(String accountId, String reason, UUID actorId) {
    this.accountId = accountId;
    this.reason = reason;
    this.actorId = actorId;
    this.createdAt = Instant.now();
  }
}
