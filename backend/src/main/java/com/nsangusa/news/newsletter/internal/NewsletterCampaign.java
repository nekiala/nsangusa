package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "newsletter_campaigns")
class NewsletterCampaign {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  String campaignKey;

  UUID articleId;

  @Column(nullable = false)
  String campaignType;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  String status;

  Instant completedAt;

  protected NewsletterCampaign() {}

  NewsletterCampaign(String campaignKey, UUID articleId, String campaignType) {
    this.id = UUID.randomUUID();
    this.campaignKey = campaignKey;
    this.articleId = articleId;
    this.campaignType = campaignType;
    this.createdAt = Instant.now();
    this.status = "dispatching";
  }

  void complete() {
    status = "completed";
    completedAt = Instant.now();
  }
}
