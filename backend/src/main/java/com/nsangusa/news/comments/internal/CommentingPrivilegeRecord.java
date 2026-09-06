package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "commenting_privilege_records")
class CommentingPrivilegeRecord {
  @Id UUID id;

  @Column(nullable = false)
  UUID userId;

  @Column(nullable = false)
  String action;

  Instant suspendedUntil;

  @Column(nullable = false, columnDefinition = "text")
  String reason;

  @Column(nullable = false)
  UUID moderatorId;

  @Column(nullable = false)
  Instant createdAt;

  protected CommentingPrivilegeRecord() {}

  CommentingPrivilegeRecord(
      UUID userId, String action, Instant suspendedUntil, String reason, UUID moderatorId) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.action = action;
    this.suspendedUntil = suspendedUntil;
    this.reason = reason;
    this.moderatorId = moderatorId;
    this.createdAt = Instant.now();
  }
}
