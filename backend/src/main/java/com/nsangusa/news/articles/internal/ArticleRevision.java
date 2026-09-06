package com.nsangusa.news.articles.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "article_revisions")
class ArticleRevision {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "article_id")
  Article article;

  @Column(nullable = false)
  int revisionNumber;

  @Column(nullable = false)
  String headline;

  @Column(nullable = false, columnDefinition = "text")
  String summary;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @Column(nullable = false)
  String reason;

  UUID actorId;

  @Column(nullable = false)
  Instant createdAt;

  protected ArticleRevision() {}

  ArticleRevision(
      UUID id,
      Article article,
      int revisionNumber,
      String headline,
      String summary,
      String body,
      String reason,
      UUID actorId,
      Instant createdAt) {
    this.id = id;
    this.article = article;
    this.revisionNumber = revisionNumber;
    this.headline = headline;
    this.summary = summary;
    this.body = body;
    this.reason = reason;
    this.actorId = actorId;
    this.createdAt = createdAt;
  }
}
