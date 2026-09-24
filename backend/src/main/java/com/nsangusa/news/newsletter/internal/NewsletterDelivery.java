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

  @Column(nullable = false, unique = true)
  String providerIdempotencyKey;

  @Column(nullable = false)
  boolean providerIdempotencyApplied;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  int attemptCount;

  UUID attemptToken;
  Instant attemptStartedAt;

  @Column(nullable = false)
  Instant createdAt;

  Instant deliveredAt;
  Instant deliveryConfirmedAt;
  String providerMessageId;
  String failureCode;

  protected NewsletterDelivery() {}

  NewsletterDelivery(UUID subscriptionId, UUID articleId, String campaignKey) {
    this.id = UUID.randomUUID();
    this.subscriptionId = subscriptionId;
    this.articleId = articleId;
    this.campaignKey = campaignKey;
    this.providerIdempotencyKey = providerIdempotencyKey(subscriptionId, campaignKey);
    this.providerIdempotencyApplied = false;
    this.status = "pending";
    this.attemptCount = 0;
    this.createdAt = Instant.now();
  }

  UUID beginAttempt(Instant startedAt) {
    attemptCount++;
    providerIdempotencyApplied = true;
    attemptToken = UUID.randomUUID();
    attemptStartedAt = startedAt;
    status = "pending";
    failureCode = null;
    return attemptToken;
  }

  boolean attemptInProgress(Instant now, java.time.Duration lease) {
    return "pending".equals(status)
        && attemptToken != null
        && attemptStartedAt != null
        && attemptStartedAt.isAfter(now.minus(lease));
  }

  boolean delivered(UUID token, String providerMessageId) {
    if (!"pending".equals(status) || !java.util.Objects.equals(attemptToken, token)) {
      return false;
    }
    this.status = "delivered";
    this.deliveredAt = Instant.now();
    this.providerMessageId = providerMessageId;
    return true;
  }

  boolean failed(UUID token, String failureCode) {
    if (!"pending".equals(status) || !java.util.Objects.equals(attemptToken, token)) {
      return false;
    }
    this.status = "failed";
    this.failureCode = failureCode;
    return true;
  }

  void bounced(String failureCode) {
    this.status = "bounced";
    this.failureCode = failureCode;
  }

  void confirmDelivery() {
    if ("delivered".equals(status) && deliveryConfirmedAt == null) {
      deliveryConfirmedAt = Instant.now();
    }
  }

  void requireReconciliation(String failureCode) {
    this.status = "reconciliation_required";
    this.failureCode = failureCode;
  }

  private static String providerIdempotencyKey(UUID subscriptionId, String campaignKey) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(
                  (subscriptionId + ":" + campaignKey)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "newsletter-" + java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
