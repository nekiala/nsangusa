package com.nsangusa.news.media;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaService {
  UUID requireGenerationId(UUID articleId, String objectKey);

  UUID requestRegeneration(UUID articleId, String prompt, String altText, UUID actorId);

  /** The caller must lock the article and validate editorial eligibility before storage writes. */
  UUID requestFallback(UUID articleId, String altText, String reason, UUID actorId);

  void approve(UUID generationId, UUID actorId);

  void deleteAsset(UUID assetId, UUID actorId);

  List<GenerationView> generations(UUID articleId);

  ImageContent image(String selectedObjectKey, String variant);

  ImageContent generationImage(UUID generationId, String variant);

  record ImageContent(byte[] bytes, String contentType) {}

  record GenerationView(
      UUID id,
      UUID articleId,
      String prompt,
      String altText,
      String objectKey,
      String provider,
      String model,
      String safetyStatus,
      Instant createdAt,
      Instant approvedAt) {}
}
