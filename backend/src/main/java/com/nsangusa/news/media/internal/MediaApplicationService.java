package com.nsangusa.news.media.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleImageCandidateGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.media.MediaService;
import com.nsangusa.news.media.ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MediaApplicationService implements MediaService {
  private final ImageGenerationRepository generations;
  private final MediaAssetRepository assets;
  private final ObjectStorage storage;
  private final DurableEventPublisher events;
  private final AuditService audit;
  private final ImageVariantProcessor variants;
  private final NeutralEditorialIllustration fallback = new NeutralEditorialIllustration();

  MediaApplicationService(
      ImageGenerationRepository generations,
      MediaAssetRepository assets,
      ObjectStorage storage,
      DurableEventPublisher events,
      AuditService audit,
      ImageVariantProcessor variants) {
    this.generations = generations;
    this.assets = assets;
    this.storage = storage;
    this.events = events;
    this.audit = audit;
    this.variants = variants;
  }

  @Override
  @Transactional(readOnly = true)
  public UUID requireGenerationId(UUID articleId, String objectKey) {
    return generations
        .findFirstByArticleIdAndObjectKeyOrderByCreatedAtDesc(articleId, objectKey)
        .map(generation -> generation.id)
        .orElseThrow(() -> new IllegalStateException("Image generation missing"));
  }

  @Override
  @Transactional
  public UUID requestRegeneration(UUID articleId, String prompt, String altText, UUID actorId) {
    ImageWorkflowConsumer.validatePrompt(prompt);
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
  public UUID requestFallback(UUID articleId, String altText, String reason, UUID actorId) {
    if (articleId == null || actorId == null) {
      throw new IllegalArgumentException("Article and editor are required");
    }
    if (altText == null || altText.isBlank() || altText.length() > 500) {
      throw new IllegalArgumentException(
          "Image alternative text must contain between 1 and 500 characters");
    }
    if (reason == null || reason.isBlank() || reason.length() > 2000) {
      throw new IllegalArgumentException(
          "Fallback selection reason must contain between 1 and 2000 characters");
    }
    UUID generationId = UUID.randomUUID();
    var generatedVariants = variants.variants(fallback.render(), "image/png");
    var storedAssets = new java.util.ArrayList<MediaAsset>();
    String heroKey = null;
    for (var variant : generatedVariants) {
      String key = "articles/" + articleId + "/" + variant.name() + "-" + generationId + ".png";
      storage.put(key, variant.bytes(), variant.contentType());
      storedAssets.add(
          new MediaAsset(
              articleId,
              generationId,
              variant.name(),
              key,
              variant.contentType(),
              variant.width(),
              variant.height(),
              StoredImages.sha256(variant.bytes())));
      if ("hero".equals(variant.name())) {
        heroKey = key;
      }
    }
    var generation =
        new ImageGeneration(
            generationId,
            articleId,
            NeutralEditorialIllustration.PROMPT,
            altText.strip(),
            java.util.Objects.requireNonNull(heroKey),
            NeutralEditorialIllustration.PROVIDER,
            NeutralEditorialIllustration.MODEL);
    generation.renderedPrompt = NeutralEditorialIllustration.PROMPT;
    generation.promptVersion = NeutralEditorialIllustration.MODEL;
    generations.save(generation);
    assets.saveAll(storedAssets);
    UUID eventId =
        events.enqueue(
            "ArticleImageCandidateGenerated",
            articleId,
            articleId,
            null,
            "article-image-fallback:" + generationId,
            new ArticleImageCandidateGenerated(
                articleId,
                generationId,
                generation.objectKey,
                generation.altText,
                generation.provider,
                generation.model,
                generation.createdAt));
    audit.record(
        actorId,
        "IMAGE_FALLBACK_REQUESTED",
        "article",
        articleId,
        Map.of(
            "reason", reason.strip(),
            "generationId", generationId.toString(),
            "altText", generation.altText,
            "provider", generation.provider,
            "model", generation.model,
            "rights", NeutralEditorialIllustration.RIGHTS));
    return eventId;
  }

  @Override
  @Transactional
  public void approve(UUID generationId, UUID actorId) {
    var generation =
        generations
            .findLockedById(generationId)
            .orElseThrow(() -> new IllegalArgumentException("Image generation not found"));
    if (!"approved".equals(generation.safetyStatus)) {
      content(generation, "hero");
    }
    if (generation.approve(actorId)) {
      events.enqueue(
          "ArticleImageApproved",
          generation.articleId,
          generation.articleId,
          null,
          "article-image-approved:" + generation.id,
          new ArticleImageApproved(
              generation.articleId,
              generation.id,
              generation.objectKey,
              generation.altText,
              actorId,
              generation.approvedAt,
              !(NeutralEditorialIllustration.PROVIDER.equals(generation.provider)
                  && NeutralEditorialIllustration.MODEL.equals(generation.model))));
      audit.record(actorId, "IMAGE_APPROVED", "image_generation", generationId, Map.of());
    }
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
  public ImageContent image(String selectedObjectKey, String variant) {
    validateVariant(variant);
    if (selectedObjectKey == null || selectedObjectKey.isBlank()) {
      throw missingImage();
    }
    var generation =
        generations
            .findFirstByObjectKeyOrderByCreatedAtDesc(selectedObjectKey)
            .filter(candidate -> "approved".equals(candidate.safetyStatus))
            .orElseThrow(MediaApplicationService::missingImage);
    return content(generation, variant);
  }

  @Override
  @Transactional(readOnly = true)
  public ImageContent generationImage(UUID generationId, String variant) {
    validateVariant(variant);
    return content(
        generations.findById(generationId).orElseThrow(MediaApplicationService::missingImage),
        variant);
  }

  private ImageContent content(ImageGeneration generation, String variant) {
    var asset =
        assets
            .findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, variant)
            .filter(
                candidate ->
                    generation.articleId.equals(candidate.articleId)
                        && generation.id.equals(candidate.generationId)
                        && variant.equals(candidate.variantName))
            .orElseThrow(MediaApplicationService::missingImage);
    if ("hero".equals(variant) && !generation.objectKey.equals(asset.objectKey)) {
      throw missingImage();
    }
    if (!"image/png".equals(asset.mediaType) && !"image/svg+xml".equals(asset.mediaType)) {
      throw missingImage();
    }
    var stored = storage.read(asset.objectKey).orElseThrow(MediaApplicationService::missingImage);
    StoredImages.validateImage(asset.objectKey, stored.bytes(), stored.contentType());
    if (!asset.mediaType.equals(stored.contentType())) {
      throw new IllegalStateException("Stored image content type does not match its asset");
    }
    if (asset.sha256 != null && !asset.sha256.equals(StoredImages.sha256(stored.bytes()))) {
      throw new IllegalStateException("Stored image checksum does not match its asset");
    }
    return new ImageContent(stored.bytes(), asset.mediaType);
  }

  private static void validateVariant(String variant) {
    if (!"hero".equals(variant) && !"thumbnail".equals(variant) && !"social".equals(variant)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown image variant");
    }
  }

  private static ResponseStatusException missingImage() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
  }
}
