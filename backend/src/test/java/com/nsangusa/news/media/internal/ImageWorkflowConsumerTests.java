package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.media.ImageGenerationProvider;
import com.nsangusa.news.media.ObjectStorage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageWorkflowConsumerTests {
  @Mock IncomingEventReader reader;
  @Mock ProcessedEventRegistry processed;
  @Mock DurableEventPublisher events;
  @Mock ImageGenerationProvider provider;
  @Mock ObjectStorage storage;
  @Mock ImageGenerationRepository generations;
  @Mock MediaAssetRepository assets;
  @Mock ImageVariantProcessor variants;

  @Test
  void providerFailureIsRetriedInsteadOfCreatingFakeMedia() {
    UUID eventId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    var payload = new ArticleImageRequested(articleId, "Editorial illustration", "Article image");
    var event =
        new EventEnvelope<>(
            eventId,
            "ArticleImageRequested",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "article-image:" + articleId,
            payload);
    var failure = new IllegalStateException("provider unavailable");
    when(reader.eventType("event")).thenReturn("ArticleImageRequested");
    when(reader.read("event", ArticleImageRequested.class)).thenReturn(event);
    when(provider.generate(payload.prompt(), payload.altText())).thenThrow(failure);

    var consumer =
        new ImageWorkflowConsumer(
            reader, processed, events, provider, storage, generations, assets, variants);

    assertThatThrownBy(() -> consumer.consume("event")).isSameAs(failure);
    verify(processed, never()).markProcessed(eventId, "image-generation-v1");
    verifyNoInteractions(events, storage, generations, assets, variants);
  }

  @Test
  void persistedVariantsRemainAssociatedWithTheirUnapprovedGeneration() {
    UUID eventId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    var payload = new ArticleImageRequested(articleId, "Editorial illustration", "Article image");
    var event =
        new EventEnvelope<>(
            eventId,
            "ArticleImageRequested",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "article-image:" + articleId,
            payload);
    byte[] source = {1, 2};
    var image =
        new ImageGenerationProvider.GeneratedImage(
            source,
            "image/png",
            "openai",
            "gpt-image-1",
            "Article image",
            "Rendered safe prompt",
            "editorial-illustration-v1",
            "req-123");
    when(reader.eventType("event")).thenReturn("ArticleImageRequested");
    when(reader.read("event", ArticleImageRequested.class)).thenReturn(event);
    when(provider.generate(payload.prompt(), payload.altText())).thenReturn(image);
    when(variants.variants(source, "image/png"))
        .thenReturn(
            java.util.List.of(
                new ImageVariantProcessor.Variant("hero", 1600, 900, source, "image/png"),
                new ImageVariantProcessor.Variant("thumbnail", 640, 360, source, "image/png"),
                new ImageVariantProcessor.Variant("social", 1200, 630, source, "image/png")));
    var consumer =
        new ImageWorkflowConsumer(
            reader, processed, events, provider, storage, generations, assets, variants);

    consumer.consume("event");

    var generation = ArgumentCaptor.forClass(ImageGeneration.class);
    verify(generations).save(generation.capture());
    assertThat(generation.getValue().safetyStatus).isEqualTo("review_required");
    assertThat(generation.getValue().approvedAt).isNull();
    assertThat(generation.getValue().renderedPrompt).isEqualTo("Rendered safe prompt");
    assertThat(generation.getValue().providerRequestId).isEqualTo("req-123");
    assertThat(generation.getValue().promptVersion).isEqualTo("editorial-illustration-v1");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<MediaAsset>> storedAssets = ArgumentCaptor.forClass(Iterable.class);
    verify(assets).saveAll(storedAssets.capture());
    assertThat(storedAssets.getValue())
        .hasSize(3)
        .allSatisfy(
            asset -> {
              assertThat(asset.articleId).isEqualTo(articleId);
              assertThat(asset.generationId).isEqualTo(generation.getValue().id);
              assertThat(asset.variantName).isIn("hero", "thumbnail", "social");
              assertThat(asset.sha256).hasSize(64);
              verify(storage).put(asset.objectKey, source, "image/png");
            });
    verify(processed).markProcessed(eventId, "image-generation-v1");
  }
}
