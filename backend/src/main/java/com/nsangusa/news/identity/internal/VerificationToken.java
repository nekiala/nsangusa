package com.nsangusa.news.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_tokens")
class VerificationToken {
  @Id UUID id;

  @Column(nullable = false)
  UUID userId;

  @Column(nullable = false, unique = true)
  String tokenHash;

  @Column(nullable = false)
  String purpose;

  @Column(nullable = false)
  Instant expiresAt;

  Instant usedAt;

  protected VerificationToken() {}

  VerificationToken(UUID userId, String tokenHash, String purpose, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.purpose = purpose;
    this.expiresAt = expiresAt;
  }

  void consume(String expectedPurpose) {
    if (!purpose.equals(expectedPurpose) || usedAt != null || !expiresAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Token is invalid or expired");
    }
    usedAt = Instant.now();
  }
}
