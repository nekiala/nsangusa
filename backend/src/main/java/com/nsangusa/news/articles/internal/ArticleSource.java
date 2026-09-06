package com.nsangusa.news.articles.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "article_sources")
class ArticleSource {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "article_id")
  Article article;

  UUID sourcePostId;
  String account;
  String postId;
  String url;
  Instant publishedAt;

  protected ArticleSource() {}

  ArticleSource(
      UUID id,
      Article article,
      UUID sourcePostId,
      String account,
      String postId,
      String url,
      Instant publishedAt) {
    this.id = id;
    this.article = article;
    this.sourcePostId = sourcePostId;
    this.account = account;
    this.postId = postId;
    this.url = url;
    this.publishedAt = publishedAt;
  }
}
