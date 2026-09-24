package com.nsangusa.news.articles.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleImageGenerated;
import com.nsangusa.news.media.MediaService;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleEventConsumerTests {
  @Test
  void delayedApprovalCannotReplaceTheMoreRecentlySelectedImage() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var articles = mock(ArticleRepository.class);
    var sources = mock(SourceIngestionService.class);
    var media = mock(MediaService.class);
    var article = mock(Article.class);
    UUID articleId = UUID.randomUUID();
    UUID oldGeneration = UUID.randomUUID();
    article.approvedImageGenerationId = UUID.randomUUID();
    var payload =
        new ArticleImageApproved(
            articleId,
            oldGeneration,
            "articles/old/hero.png",
            "Older image",
            UUID.randomUUID(),
            Instant.now().minusSeconds(30));
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleImageApproved",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "media",
            Map.of(),
            "old-image-approval",
            payload);
    when(reader.eventType("event")).thenReturn("ArticleImageApproved");
    when(reader.read("event", ArticleImageApproved.class)).thenReturn(event);
    when(articles.findById(articleId)).thenReturn(Optional.of(article));

    new ArticleEventConsumer(reader, processed, events, articles, sources, media)
        .approvedImages("event");

    verify(article, never())
        .imageApproved(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(processed).markProcessed(event.eventId(), "article-image-approval-v1");
    org.mockito.Mockito.verifyNoInteractions(events);
  }

  @Test
  void approvalPreservesFallbackDisclosureAndDefaultsLegacyEventsToGenerated() {
    for (Boolean generated : new Boolean[] {false, true, null}) {
      var reader = mock(IncomingEventReader.class);
      var processed = mock(ProcessedEventRegistry.class);
      var events = mock(DurableEventPublisher.class);
      var articles = mock(ArticleRepository.class);
      var article = mock(Article.class);
      UUID articleId = UUID.randomUUID();
      UUID generationId = UUID.randomUUID();
      var payload =
          new ArticleImageApproved(
              articleId,
              generationId,
              "articles/hero.png",
              "Illustration",
              UUID.randomUUID(),
              Instant.now(),
              generated);
      var event =
          new EventEnvelope<>(
              UUID.randomUUID(),
              "ArticleImageApproved",
              1,
              articleId,
              articleId,
              null,
              Instant.now(),
              "media",
              Map.of(),
              "image-approval:" + generationId,
              payload);
      when(reader.eventType("event")).thenReturn("ArticleImageApproved");
      when(reader.read("event", ArticleImageApproved.class)).thenReturn(event);
      when(articles.findById(articleId)).thenReturn(Optional.of(article));
      new ArticleEventConsumer(
              reader,
              processed,
              events,
              articles,
              mock(SourceIngestionService.class),
              mock(MediaService.class))
          .approvedImages("event");
      verify(article)
          .imageApproved(
              generationId, "articles/hero.png", "Illustration", !Boolean.FALSE.equals(generated));
      verify(processed).markProcessed(event.eventId(), "article-image-approval-v1");
    }
  }

  @Test
  void legacyImageEventResolvesThePersistedGenerationInsteadOfUsingTheEventId() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var articles = mock(ArticleRepository.class);
    var sources = mock(SourceIngestionService.class);
    var media = mock(MediaService.class);
    var article = mock(Article.class);
    UUID articleId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    String objectKey = "articles/legacy/hero.png";
    var payload =
        new ArticleImageGenerated(
            articleId, null, objectKey, "Legacy alt", "provider", "model", Instant.now());
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleImageGenerated",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "legacy",
            Map.of(),
            "legacy-image:" + articleId,
            payload);
    when(reader.eventType("event")).thenReturn("ArticleImageGenerated");
    when(reader.read("event", ArticleImageGenerated.class)).thenReturn(event);
    when(articles.findById(articleId)).thenReturn(Optional.of(article));
    when(media.requireGenerationId(articleId, objectKey)).thenReturn(generationId);

    new ArticleEventConsumer(reader, processed, events, articles, sources, media).images("event");

    verify(media).requireGenerationId(articleId, objectKey);
    verify(article).imageCandidateReady(generationId);
    verify(processed).markProcessed(event.eventId(), "article-image-result-v1");
  }
}
