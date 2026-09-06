package com.nsangusa.news.comments.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "article_comment_settings")
class ArticleCommentSettings {
  @Id UUID articleId;
  Boolean enabledOverride;
  Boolean requireApprovalOverride;
  Instant updatedAt;
  UUID updatedBy;
  @Version long version;

  protected ArticleCommentSettings() {}

  ArticleCommentSettings(UUID articleId) {
    this.articleId = articleId;
    this.updatedAt = Instant.now();
  }

  void update(Boolean enabledOverride, Boolean requireApprovalOverride, UUID updatedBy) {
    this.enabledOverride = enabledOverride;
    this.requireApprovalOverride = requireApprovalOverride;
    this.updatedBy = updatedBy;
    this.updatedAt = Instant.now();
  }
}
