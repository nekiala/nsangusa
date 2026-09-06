package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "newsletter_suppressions")
class SuppressionEntry {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  String emailHash;

  @Column(nullable = false)
  String reason;

  @Column(nullable = false)
  Instant createdAt;

  protected SuppressionEntry() {}

  SuppressionEntry(String emailHash, String reason) {
    this.id = UUID.randomUUID();
    this.emailHash = emailHash;
    this.reason = reason;
    this.createdAt = Instant.now();
  }
}
