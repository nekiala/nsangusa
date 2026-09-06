package com.nsangusa.news.search.internal;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.search.SearchService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchEventConsumerTests {
  @Mock IncomingEventReader reader;
  @Mock ProcessedEventRegistry processed;
  @Mock SearchService search;

  @Test
  void indexesPublishedEventsExactlyOnce() {
    String json = "{}";
    UUID eventId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    when(reader.eventType(json)).thenReturn("ArticlePublished");
    when(reader.read(json, ArticlePublished.class))
        .thenReturn(
            envelope(
                eventId,
                "ArticlePublished",
                articleId,
                new ArticlePublished(articleId, "slug", "Headline", Instant.now(), true)));

    new SearchEventConsumer(reader, processed, search).consume(json);

    verify(search).indexPublished(articleId);
    verify(processed).markProcessed(eventId, "search-index-v1");
  }

  @Test
  void ignoresAlreadyProcessedEvents() {
    String json = "{}";
    UUID eventId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    when(reader.eventType(json)).thenReturn("ArticlePublished");
    when(reader.read(json, ArticlePublished.class))
        .thenReturn(
            envelope(
                eventId,
                "ArticlePublished",
                articleId,
                new ArticlePublished(articleId, "slug", "Headline", Instant.now(), true)));
    when(processed.wasProcessed(eventId, "search-index-v1")).thenReturn(true);

    new SearchEventConsumer(reader, processed, search).consume(json);

    verify(search, never()).indexPublished(articleId);
  }

  @Test
  void removesUnpublishedEvents() {
    String json = "{}";
    UUID eventId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    when(reader.eventType(json)).thenReturn("ArticleUnpublished");
    when(reader.read(json, ArticleUnpublished.class))
        .thenReturn(
            envelope(eventId, "ArticleUnpublished", articleId, new ArticleUnpublished(articleId)));

    new SearchEventConsumer(reader, processed, search).consume(json);

    verify(search).removeUnpublished(articleId);
    verify(processed).markProcessed(eventId, "search-index-v1");
  }

  private static <T> EventEnvelope<T> envelope(
      UUID eventId, String type, UUID articleId, T payload) {
    return new EventEnvelope<>(
        eventId,
        type,
        1,
        articleId,
        articleId,
        null,
        Instant.now(),
        "test",
        Map.of(),
        "test:" + eventId,
        payload);
  }
}
