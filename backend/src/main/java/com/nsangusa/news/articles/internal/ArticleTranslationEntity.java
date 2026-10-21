package com.nsangusa.news.articles.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The reader-facing text of an article in a language other than its primary one. */
@Entity
@Table(name = "article_translations")
class ArticleTranslationEntity {
  @Id UUID id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "article_id")
  Article article;

  @Column(nullable = false)
  String language;

  @Column(nullable = false)
  String headline;

  @Column(nullable = false, columnDefinition = "text")
  String summary;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @Column(columnDefinition = "text")
  String editorialContext;

  @Column(nullable = false)
  String seoTitle;

  @Column(nullable = false)
  String seoDescription;

  String imageAltText;

  @Column(nullable = false)
  Instant updatedAt;

  protected ArticleTranslationEntity() {}

  ArticleTranslationEntity(Article article, String language) {
    this.id = UUID.randomUUID();
    this.article = article;
    this.language = language;
  }
}
