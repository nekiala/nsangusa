package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_posts")
class SourcePost {
  @Id UUID id;

  @Column(nullable = false)
  UUID monitoredAccountId;

  @Column(nullable = false, unique = true)
  String postId;

  @Column(nullable = false)
  String accountId;

  @Column(nullable = false)
  String handle;

  @Column(nullable = false)
  String canonicalUrl;

  @Column(nullable = false, columnDefinition = "text")
  String permittedText;

  @Column(nullable = false)
  Instant publishedAt;

  @Column(nullable = false)
  Instant ingestedAt;

  @Column(nullable = false)
  String status;

  String complianceAction;
  String reconciliationState;
  Instant complianceRequestedAt;
  Instant complianceReconciledAt;

  @Column(length = 500)
  String complianceReason;

  String complianceError;
  Instant excludedAt;
  Instant contentDeletedAt;

  @Version long version;

  protected SourcePost() {}

  SourcePost(
      UUID id,
      UUID monitoredAccountId,
      String postId,
      String accountId,
      String handle,
      String canonicalUrl,
      String permittedText,
      Instant publishedAt) {
    this.id = id;
    this.monitoredAccountId = monitoredAccountId;
    this.postId = postId;
    this.accountId = accountId;
    this.handle = handle;
    this.canonicalUrl = canonicalUrl;
    this.permittedText = permittedText;
    this.publishedAt = publishedAt;
    this.ingestedAt = Instant.now();
    this.status = "active";
  }
}
