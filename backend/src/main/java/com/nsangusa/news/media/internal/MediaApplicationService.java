package com.nsangusa.news.media.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.media.MediaService;
import com.nsangusa.news.media.ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MediaApplicationService implements MediaService {
  private final ImageGenerationRepository generations;
  private final MediaAssetRepository assets;
  private final ObjectStorage storage;
  private final DurableEventPublisher events;
  private final AuditService audit;

  MediaApplicationService(
      ImageGenerationRepository generations,
      MediaAssetRepository assets,
      ObjectStorage storage,
      DurableEventPublisher events,
      AuditService audit) {
    this.generations = generations;
    this.assets = assets;
    this.storage = storage;
    this.events = events;
    this.audit = audit;
  }

  @Override
  @Transactional
  public UUID requestRegeneration(UUID articleId, String prompt, String altText, UUID actorId) {
    UUID eventId =
        events.enqueue(
            "ArticleImageRequested",
            articleId,
            articleId,
            null,
            "article-image-regeneration:" + articleId + ":" + UUID.randomUUID(),
            new ArticleImageRequested(articleId, prompt, altText));
    audit.record(actorId, "IMAGE_REGENERATION_REQUESTED", "article", articleId, Map.of());
    return eventId;
  }

  @Override
  @Transactional
  public void approve(UUID generationId, UUID actorId) {
    var generation =
        generations
            .findById(generationId)
            .orElseThrow(() -> new IllegalArgumentException("Image generation not found"));
    generation.approve(actorId);
    audit.record(actorId, "IMAGE_APPROVED", "image_generation", generationId, Map.of());
  }

  @Override
  @Transactional
  public void deleteAsset(UUID assetId, UUID actorId) {
    var asset =
        assets.findById(assetId).orElseThrow(() -> new IllegalArgumentException("Asset not found"));
    storage.delete(asset.objectKey);
    asset.softDelete();
    audit.record(actorId, "MEDIA_ASSET_DELETED", "media_asset", assetId, Map.of());
  }

  @Override
  @Transactional(readOnly = true)
  public List<GenerationView> generations(UUID articleId) {
    return generations.findByArticleIdOrderByCreatedAtDesc(articleId).stream()
        .map(
            generation ->
                new GenerationView(
                    generation.id,
                    generation.articleId,
                    generation.prompt,
                    generation.altText,
                    generation.objectKey,
                    generation.provider,
                    generation.model,
                    generation.safetyStatus,
                    generation.createdAt,
                    generation.approvedAt))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isApprovedOrAbsent(UUID articleId) {
    return !generations.existsByArticleId(articleId)
        || generations.existsByArticleIdAndSafetyStatus(articleId, "approved");
  }
}
