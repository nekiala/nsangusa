package com.nsangusa.news.media.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "image_generations")
class ImageGeneration {
  @Id UUID id;

  @Column(nullable = false)
  UUID articleId;

  @Column(nullable = false, columnDefinition = "text")
  String prompt;

  @Column(nullable = false)
  String altText;

  @Column(nullable = false)
  String objectKey;

  @Column(nullable = false)
  String provider;

  @Column(nullable = false)
  String model;

  @Column(columnDefinition = "text")
  String renderedPrompt;

  @Column(length = 100)
  String promptVersion;

  @Column(length = 200)
  String providerRequestId;

  @Column(nullable = false)
  String safetyStatus;

  @Column(nullable = false)
  Instant createdAt;

  Instant approvedAt;
  UUID approvedBy;

  @Column(nullable = false)
  boolean approvalEventExpected;

  protected ImageGeneration() {}

  ImageGeneration(
      UUID articleId,
      String prompt,
      String altText,
      String objectKey,
      String provider,
      String model) {
    this(UUID.randomUUID(), articleId, prompt, altText, objectKey, provider, model);
  }

  ImageGeneration(
      UUID id,
      UUID articleId,
      String prompt,
      String altText,
      String objectKey,
      String provider,
      String model) {
    this.id = id;
    this.articleId = articleId;
    this.prompt = prompt;
    this.altText = altText;
    this.objectKey = objectKey;
    this.provider = provider;
    this.model = model;
    this.safetyStatus = "review_required";
    this.approvalEventExpected = false;
    this.createdAt = Instant.now();
  }

  boolean approve(UUID actorId) {
    if ("approved".equals(safetyStatus)) {
      if (!actorId.equals(approvedBy)) {
        throw new IllegalStateException("Image generation is already approved");
      }
      return false;
    }
    approvalEventExpected = true;
    safetyStatus = "approved";
    approvedAt = Instant.now();
    approvedBy = actorId;
    return true;
  }
}
