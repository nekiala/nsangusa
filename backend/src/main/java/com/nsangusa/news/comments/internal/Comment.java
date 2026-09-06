package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comments")
class Comment {
  @Id UUID id;

  @Column(nullable = false)
  UUID articleId;

  @Column(nullable = false)
  UUID authorId;

  UUID parentId;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @Column(nullable = false)
  String state;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  Instant updatedAt;

  Instant editedAt;
  Instant deletedAt;

  @Column(nullable = false)
  boolean deletedByAuthor;

  @Column(nullable = false)
  double spamScore;

  String spamReason;

  @Version long version;

  protected Comment() {}

  Comment(
      UUID articleId,
      UUID authorId,
      String body,
      UUID parentId,
      String initialState,
      double spamScore,
      String spamReason) {
    this.id = UUID.randomUUID();
    this.articleId = articleId;
    this.authorId = authorId;
    this.body = body;
    this.parentId = parentId;
    this.state = initialState;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
    this.spamScore = spamScore;
    this.spamReason = spamReason;
  }
}
