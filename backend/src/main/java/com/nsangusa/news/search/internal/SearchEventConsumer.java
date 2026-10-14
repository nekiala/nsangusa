package com.nsangusa.news.search.internal;

import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.search.SearchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SearchEventConsumer {
  private static final String CONSUMER = "search-index-v1";
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final SearchService search;

  SearchEventConsumer(
      IncomingEventReader reader, ProcessedEventRegistry processed, SearchService search) {
    this.reader = reader;
    this.processed = processed;
    this.search = search;
  }

  @KafkaListener(topics = EventTopics.PUBLICATION, groupId = CONSUMER)
  @KafkaListener(
      topics = EventTopics.PUBLICATION_RETRY,
      groupId = CONSUMER + EventTopics.RETRY_GROUP_SUFFIX)
  @Transactional
  void consume(String json) {
    String eventType = reader.eventType(json);
    if ("ArticlePublished".equals(eventType)) {
      var event = reader.read(json, ArticlePublished.class);
      if (processed.wasProcessed(event.eventId(), CONSUMER)) {
        return;
      }
      search.indexPublished(event.payload().articleId());
      processed.markProcessed(event.eventId(), CONSUMER);
    } else if ("ArticleUnpublished".equals(eventType)) {
      var event = reader.read(json, ArticleUnpublished.class);
      if (processed.wasProcessed(event.eventId(), CONSUMER)) {
        return;
      }
      search.removeUnpublished(event.payload().articleId());
      processed.markProcessed(event.eventId(), CONSUMER);
    }
  }
}
