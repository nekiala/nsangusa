package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_tombstones")
class SourceTombstone {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  UUID sourcePostId;

  @Column(nullable = false, unique = true)
  String postId;

  @Column(nullable = false)
  String accountId;

  @Column(nullable = false)
  String reason;

  @Column(nullable = false)
  Instant deletedAt;

  UUID actorId;

  protected SourceTombstone() {}

  SourceTombstone(
      UUID id, UUID sourcePostId, String postId, String accountId, String reason, UUID actorId) {
    this.id = id;
    this.sourcePostId = sourcePostId;
    this.postId = postId;
    this.accountId = accountId;
    this.reason = reason;
    this.actorId = actorId;
    this.deletedAt = Instant.now();
  }
}
