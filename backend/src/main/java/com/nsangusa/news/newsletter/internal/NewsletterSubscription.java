package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Entity
@Table(name = "newsletter_subscriptions")
class NewsletterSubscription {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  String email;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  String frequency;

  @Column(nullable = false)
  String consentSource;

  @Column(nullable = false)
  Instant consentAt;

  @Column(nullable = false)
  String verificationTokenHash;

  @Column(nullable = false)
  String unsubscribeTokenHash;

  Instant verifiedAt;
  Instant unsubscribedAt;

  @Version long version;

  protected NewsletterSubscription() {}

  NewsletterSubscription(
      UUID id,
      String email,
      String source,
      String frequency,
      String verificationToken,
      String unsubscribeToken) {
    this.id = id;
    this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
    this.status = "pending";
    this.frequency = frequency;
    this.consentSource = source;
    this.consentAt = Instant.now();
    this.verificationTokenHash = hash(verificationToken);
    this.unsubscribeTokenHash = hash(unsubscribeToken);
  }

  void confirm(String token) {
    if (!MessageDigest.isEqual(
        verificationTokenHash.getBytes(StandardCharsets.UTF_8),
        hash(token).getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Invalid verification token");
    }
    status = "confirmed";
    verifiedAt = Instant.now();
  }

  void unsubscribe(String token) {
    String supplied = hash(token);
    if (!MessageDigest.isEqual(
        unsubscribeTokenHash.getBytes(StandardCharsets.UTF_8),
        supplied.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Invalid unsubscribe token");
    }
    status = "unsubscribed";
    unsubscribedAt = Instant.now();
  }

  void updateFrequency(String token, String frequency) {
    String supplied = hash(token);
    if (!MessageDigest.isEqual(
        unsubscribeTokenHash.getBytes(StandardCharsets.UTF_8),
        supplied.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Invalid preference token");
    }
    if (!java.util.Set.of("immediate", "daily", "weekly", "all").contains(frequency)) {
      throw new IllegalArgumentException("Unsupported newsletter frequency");
    }
    this.frequency = frequency;
  }

  void suppress() {
    status = "suppressed";
    unsubscribedAt = Instant.now();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
