package com.nsangusa.news.media.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleImageCandidateGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.media.ImageGenerationProvider;
import com.nsangusa.news.media.ObjectStorage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ImageWorkflowConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final ImageGenerationProvider provider;
  private final ObjectStorage storage;
  private final ImageGenerationRepository generations;
  private final MediaAssetRepository assets;
  private final ImageVariantProcessor variants;

  ImageWorkflowConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      ImageGenerationProvider provider,
      ObjectStorage storage,
      ImageGenerationRepository generations,
      MediaAssetRepository assets,
      ImageVariantProcessor variants) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.provider = provider;
    this.storage = storage;
    this.generations = generations;
    this.assets = assets;
    this.variants = variants;
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "image-generation-v1")
  @Transactional
  void consume(String json) {
    if (!"ArticleImageRequested".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleImageRequested.class);
    if (processed.wasProcessed(event.eventId(), "image-generation-v1")) {
      return;
    }
    validatePrompt(event.payload().prompt());
    ImageGenerationProvider.GeneratedImage image =
        provider.generate(event.payload().prompt(), event.payload().altText());
    UUID generationId = UUID.randomUUID();
    String heroKey = null;
    var generatedVariants = variants.variants(image.bytes(), image.contentType());
    var storedAssets = new java.util.ArrayList<MediaAsset>();
    for (var variant : generatedVariants) {
      String extension = "image/svg+xml".equals(variant.contentType()) ? ".svg" : ".png";
      String key =
          "articles/"
              + event.payload().articleId()
              + "/"
              + variant.name()
              + "-"
              + event.eventId()
              + extension;
      storage.put(key, variant.bytes(), variant.contentType());
      storedAssets.add(
          new MediaAsset(
              event.payload().articleId(),
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
            event.payload().articleId(),
            event.payload().prompt(),
            image.altText(),
            java.util.Objects.requireNonNull(heroKey),
            image.provider(),
            image.model());
    generation.renderedPrompt =
        image.renderedPrompt() == null ? event.payload().prompt() : image.renderedPrompt();
    generation.promptVersion =
        image.promptVersion() == null ? "editorial-illustration-v1" : image.promptVersion();
    generation.providerRequestId = image.providerRequestId();
    generations.save(generation);
    assets.saveAll(storedAssets);
    events.enqueue(
        "ArticleImageCandidateGenerated",
        event.payload().articleId(),
        event.correlationId(),
        event.eventId(),
        "article-image-candidate-generated:" + event.eventId(),
        new ArticleImageCandidateGenerated(
            event.payload().articleId(),
            generationId,
            heroKey,
            image.altText(),
            image.provider(),
            image.model(),
            Instant.now()));
    processed.markProcessed(event.eventId(), "image-generation-v1");
  }

  static void validatePrompt(String prompt) {
    if (prompt == null || prompt.isBlank() || prompt.length() > 4_000) {
      throw new IllegalArgumentException("Image prompt must contain between 1 and 4000 characters");
    }
    String lower = prompt.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("fabricated screenshot")
        || lower.contains("add a real logo")
        || lower.contains("impersonate")
        || lower.contains("identifiable private person")) {
      throw new IllegalArgumentException("Image prompt violates editorial image policy");
    }
  }
}
