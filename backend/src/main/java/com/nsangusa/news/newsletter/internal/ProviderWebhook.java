package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_webhooks")
class ProviderWebhook {
  @Id UUID id;

  @Column(nullable = false)
  String provider;

  @Column(nullable = false)
  String externalEventId;

  @Column(nullable = false)
  boolean signatureValid;

  @Column(nullable = false)
  Instant receivedAt;

  Instant processedAt;

  @Column(nullable = false)
  String payloadHash;

  protected ProviderWebhook() {}

  ProviderWebhook(
      String provider, String externalEventId, boolean signatureValid, String payloadHash) {
    this.id = UUID.randomUUID();
    this.provider = provider;
    this.externalEventId = externalEventId;
    this.signatureValid = signatureValid;
    this.receivedAt = Instant.now();
    this.payloadHash = payloadHash;
  }

  void processed() {
    processedAt = Instant.now();
  }
}
