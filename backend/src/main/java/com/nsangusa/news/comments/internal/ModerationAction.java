package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "moderation_actions")
class ModerationAction {
  @Id UUID id;

  @Column(nullable = false)
  UUID commentId;

  @Column(nullable = false)
  UUID moderatorId;

  @Column(nullable = false)
  String action;

  String previousState;

  @Column(columnDefinition = "text")
  String reason;

  @Column(nullable = false)
  Instant createdAt;

  protected ModerationAction() {}

  ModerationAction(
      UUID commentId, UUID moderatorId, String previousState, String action, String reason) {
    this.id = UUID.randomUUID();
    this.commentId = commentId;
    this.moderatorId = moderatorId;
    this.previousState = previousState;
    this.action = action;
    this.reason = reason;
    this.createdAt = Instant.now();
  }
}
