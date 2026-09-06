package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "commenting_privileges")
class CommentingPrivilege {
  @Id UUID userId;

  @Column(nullable = false)
  String status;

  Instant suspendedUntil;

  @Column(columnDefinition = "text")
  String reason;

  UUID moderatorId;

  @Column(nullable = false)
  Instant updatedAt;

  @Version long version;

  protected CommentingPrivilege() {}

  CommentingPrivilege(UUID userId) {
    this.userId = userId;
    this.status = "allowed";
    this.updatedAt = Instant.now();
  }

  void suspend(Instant until, String reason, UUID moderatorId) {
    status = "suspended";
    suspendedUntil = until;
    this.reason = reason;
    this.moderatorId = moderatorId;
    updatedAt = Instant.now();
  }

  void restore(String reason, UUID moderatorId) {
    status = "allowed";
    suspendedUntil = null;
    this.reason = reason;
    this.moderatorId = moderatorId;
    updatedAt = Instant.now();
  }

  boolean isSuspendedAt(Instant now) {
    return "suspended".equals(status) && (suspendedUntil == null || suspendedUntil.isAfter(now));
  }
}
