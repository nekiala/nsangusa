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

  @Column(nullable = false)
  String safetyStatus;

  @Column(nullable = false)
  Instant createdAt;

  Instant approvedAt;
  UUID approvedBy;

  protected ImageGeneration() {}

  ImageGeneration(
      UUID articleId,
      String prompt,
      String altText,
      String objectKey,
      String provider,
      String model) {
    this.id = UUID.randomUUID();
    this.articleId = articleId;
    this.prompt = prompt;
    this.altText = altText;
    this.objectKey = objectKey;
    this.provider = provider;
    this.model = model;
    this.safetyStatus = "review_required";
    this.createdAt = Instant.now();
  }

  void approve(UUID actorId) {
    safetyStatus = "approved";
    approvedAt = Instant.now();
    approvedBy = actorId;
  }
}
