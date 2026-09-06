package com.nsangusa.news.media.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleImageGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.media.ImageGenerationProvider;
import com.nsangusa.news.media.ObjectStorage;
import java.time.Instant;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ImageWorkflowConsumer {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ImageWorkflowConsumer.class);
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
    ImageGenerationProvider.GeneratedImage image;
    try {
      image = provider.generate(event.payload().prompt(), event.payload().altText());
    } catch (RuntimeException exception) {
      log.warn(
          "Image provider failed for article {}; using disclosed fallback",
          event.payload().articleId(),
          exception);
      image =
          new FakeImageGenerationProvider()
              .generate(
                  "Fallback abstract editorial illustration",
                  event.payload().altText() + " (fallback image)");
    }
    String heroKey = null;
    for (var variant : variants.variants(image.bytes(), image.contentType())) {
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
      assets.save(
          new MediaAsset(
              event.payload().articleId(),
              key,
              variant.contentType(),
              variant.width(),
              variant.height(),
              sha256(variant.bytes())));
      if ("hero".equals(variant.name())) {
        heroKey = key;
      }
    }
    generations.save(
        new ImageGeneration(
            event.payload().articleId(),
            event.payload().prompt(),
            image.altText(),
            java.util.Objects.requireNonNull(heroKey),
            image.provider(),
            image.model()));
    events.enqueue(
        "ArticleImageGenerated",
        event.payload().articleId(),
        event.correlationId(),
        event.eventId(),
        "article-image-generated:" + event.payload().articleId(),
        new ArticleImageGenerated(
            event.payload().articleId(),
            heroKey,
            image.altText(),
            image.provider(),
            image.model(),
            Instant.now()));
    processed.markProcessed(event.eventId(), "image-generation-v1");
  }

  private static void validatePrompt(String prompt) {
    String lower = prompt.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("fabricated screenshot")
        || lower.contains("add a real logo")
        || lower.contains("impersonate")
        || lower.contains("identifiable private person")) {
      throw new IllegalArgumentException("Image prompt violates editorial image policy");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
