package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PublicationConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final ArticleService articles;
  private final String policy;

  PublicationConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      ArticleService articles,
      @Value("${news.publication.policy:HUMAN_REVIEW_ALWAYS}") String policy) {
    this.reader = reader;
    this.processed = processed;
    this.articles = articles;
    this.policy = policy;
  }

  @KafkaListener(topics = EventTopics.PUBLICATION, groupId = "publication-v1")
  @Transactional
  void consume(String json) {
    if (!"ArticleApproved".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleApproved.class);
    if (processed.wasProcessed(event.eventId(), "publication-v1")) {
      return;
    }
    if (policy.startsWith("AUTOMATIC")) {
      articles.publish(
          event.payload().articleId(),
          event.payload().approvedBy(),
          event.correlationId(),
          event.eventId());
    }
    processed.markProcessed(event.eventId(), "publication-v1");
  }
}
