package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_compliance_actions")
class SourceComplianceAction {
  @Id UUID id;

  @Column(nullable = false)
  String action;

  @Column(length = 100)
  String targetType;

  @Column(length = 200)
  String targetKey;

  UUID sourcePostId;
  UUID actorId;

  @Column(nullable = false, length = 500)
  String reason;

  @Column(nullable = false)
  Instant occurredAt;

  @Column(nullable = false, columnDefinition = "text")
  String metadata;

  protected SourceComplianceAction() {}

  SourceComplianceAction(
      String action,
      String targetType,
      String targetKey,
      UUID sourcePostId,
      UUID actorId,
      String reason,
      String metadata) {
    this.id = UUID.randomUUID();
    this.action = action;
    this.targetType = targetType;
    this.targetKey = targetKey;
    this.sourcePostId = sourcePostId;
    this.actorId = actorId;
    this.reason = reason;
    this.metadata = metadata;
    this.occurredAt = Instant.now();
  }
}
