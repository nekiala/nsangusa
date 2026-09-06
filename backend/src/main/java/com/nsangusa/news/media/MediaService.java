package com.nsangusa.news.media;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaService {
  UUID requestRegeneration(UUID articleId, String prompt, String altText, UUID actorId);

  void approve(UUID generationId, UUID actorId);

  void deleteAsset(UUID assetId, UUID actorId);

  List<GenerationView> generations(UUID articleId);

  boolean isApprovedOrAbsent(UUID articleId);

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
