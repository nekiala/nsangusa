package com.nsangusa.news.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_records")
class AuditEntity {
  @Id UUID id;
  UUID actorId;

  @Column(nullable = false)
  String action;

  @Column(nullable = false)
  String targetType;

  UUID targetId;

  @Column(nullable = false)
  Instant occurredAt;

  @Column(nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  String metadata;

  protected AuditEntity() {}

  AuditEntity(UUID actorId, String action, String targetType, UUID targetId, String metadata) {
    this.id = UUID.randomUUID();
    this.actorId = actorId;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.occurredAt = Instant.now();
    this.metadata = metadata;
  }
}
