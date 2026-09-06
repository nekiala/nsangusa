package com.nsangusa.news.publication.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scheduled_publications")
class ScheduledPublication {
  @Id UUID id;

  @Column(nullable = false)
  UUID articleId;

  @Column(nullable = false)
  Instant scheduledFor;

  @Column(nullable = false)
  String status;

  @Column(nullable = false, unique = true)
  String idempotencyKey;

  protected ScheduledPublication() {}

  ScheduledPublication(UUID articleId, Instant scheduledFor) {
    this.id = UUID.randomUUID();
    this.articleId = articleId;
    this.scheduledFor = scheduledFor;
    this.status = "scheduled";
    this.idempotencyKey = "scheduled-publication:" + articleId + ":" + scheduledFor;
  }
}
