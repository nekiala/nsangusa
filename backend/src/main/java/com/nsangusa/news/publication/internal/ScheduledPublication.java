package com.nsangusa.news.publication.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

  @Column(nullable = false)
  UUID scheduledBy;

  @Column(nullable = false)
  UUID updatedBy;

  @Version long version;

  Long articleVersion;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  Instant updatedAt;

  Instant completedAt;

  Instant lastAttemptAt;

  @Column(nullable = false)
  int attemptCount;

  @Column(length = 1000)
  String lastError;

  protected ScheduledPublication() {}

  ScheduledPublication(
      UUID articleId, Instant scheduledFor, UUID scheduledBy, long articleVersion) {
    this.id = UUID.randomUUID();
    this.articleId = articleId;
    this.scheduledFor = scheduledFor;
    this.status = "scheduled";
    this.idempotencyKey = "scheduled-publication:" + id;
    this.scheduledBy = scheduledBy;
    this.updatedBy = scheduledBy;
    this.articleVersion = articleVersion;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  boolean active() {
    return "scheduled".equals(status) || "failed".equals(status);
  }

  void requireVersion(long expectedVersion) {
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("Expected version must be nonnegative");
    }
    if (version != expectedVersion) {
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "Publication schedule version does not match");
    }
    if (!active()) {
      throw new IllegalStateException("Only scheduled or failed publications can be changed");
    }
  }

  void requireScheduledArticle(com.nsangusa.news.articles.ArticleService.ArticleView article) {
    if (articleVersion == null || article.version() != articleVersion) {
      throw new IllegalStateException(
          "The approved article version changed; cancel this schedule and review the article again");
    }
    if (article.state() != com.nsangusa.news.articles.ArticleState.SCHEDULED) {
      throw new IllegalStateException("The article is no longer scheduled for publication");
    }
  }

  com.nsangusa.news.publication.PublicationService.ScheduleView view() {
    return new com.nsangusa.news.publication.PublicationService.ScheduleView(
        id,
        articleId,
        scheduledFor,
        status,
        version,
        articleVersion,
        scheduledBy,
        updatedBy,
        createdAt,
        updatedAt,
        completedAt,
        lastAttemptAt,
        attemptCount,
        lastError);
  }
}
