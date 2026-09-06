package com.nsangusa.news.media.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
class MediaAsset {
  @Id UUID id;
  UUID articleId;

  @Column(nullable = false, unique = true)
  String objectKey;

  @Column(nullable = false)
  String mediaType;

  Integer width;
  Integer height;
  String sha256;

  @Column(nullable = false)
  Instant createdAt;

  Instant deletedAt;

  protected MediaAsset() {}

  MediaAsset(
      UUID articleId, String objectKey, String mediaType, int width, int height, String sha256) {
    this.id = UUID.randomUUID();
    this.articleId = articleId;
    this.objectKey = objectKey;
    this.mediaType = mediaType;
    this.width = width;
    this.height = height;
    this.sha256 = sha256;
    this.createdAt = Instant.now();
  }

  void softDelete() {
    deletedAt = Instant.now();
  }
}
