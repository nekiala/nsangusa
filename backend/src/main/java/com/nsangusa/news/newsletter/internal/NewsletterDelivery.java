package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "newsletter_deliveries")
class NewsletterDelivery {
  @Id UUID id;

  @Column(nullable = false)
  UUID subscriptionId;

  @Column(nullable = false)
  UUID articleId;

  @Column(nullable = false)
  String campaignKey;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  int attemptCount;

  @Column(nullable = false)
  Instant createdAt;

  Instant deliveredAt;
  String providerMessageId;
  String failureCode;

  protected NewsletterDelivery() {}

  NewsletterDelivery(UUID subscriptionId, UUID articleId, String campaignKey) {
    this.id = UUID.randomUUID();
    this.subscriptionId = subscriptionId;
    this.articleId = articleId;
    this.campaignKey = campaignKey;
    this.status = "pending";
    this.attemptCount = 0;
    this.createdAt = Instant.now();
  }

  void delivered(String providerMessageId) {
    this.status = "delivered";
    this.attemptCount++;
    this.deliveredAt = Instant.now();
    this.providerMessageId = providerMessageId;
  }

  void failed(String failureCode) {
    this.status = "failed";
    this.attemptCount++;
    this.failureCode = failureCode;
  }

  void bounced(String failureCode) {
    this.status = "bounced";
    this.failureCode = failureCode;
  }
}
