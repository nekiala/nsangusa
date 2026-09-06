package com.nsangusa.news.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_identities")
class ExternalIdentity {
  @Id UUID id;

  @Column(nullable = false)
  UUID userId;

  @Column(nullable = false)
  String issuer;

  @Column(nullable = false)
  String subject;

  String emailAtLink;

  @Column(nullable = false)
  Instant createdAt;

  Instant lastLoginAt;

  protected ExternalIdentity() {}

  ExternalIdentity(UUID userId, String issuer, String subject, String emailAtLink, Instant now) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.issuer = issuer;
    this.subject = subject;
    this.emailAtLink = emailAtLink;
    this.createdAt = now;
    this.lastLoginAt = now;
  }

  void recordLogin(Instant now) {
    lastLoginAt = now;
  }
}
