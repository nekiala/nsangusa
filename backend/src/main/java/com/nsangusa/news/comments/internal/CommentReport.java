package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comment_reports")
class CommentReport {
  @Id UUID id;

  @Column(nullable = false)
  UUID commentId;

  @Column(nullable = false)
  UUID reporterId;

  @Column(nullable = false)
  String reason;

  @Column(columnDefinition = "text")
  String details;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  Instant createdAt;

  Instant resolvedAt;
  UUID resolvedBy;
  @Version long version;

  protected CommentReport() {}

  CommentReport(UUID commentId, UUID reporterId, String reason, String details) {
    this.id = UUID.randomUUID();
    this.commentId = commentId;
    this.reporterId = reporterId;
    this.reason = reason;
    this.details = details;
    this.status = "open";
    this.createdAt = Instant.now();
  }

  void resolve(UUID moderatorId) {
    status = "resolved";
    resolvedBy = moderatorId;
    resolvedAt = Instant.now();
  }
}
