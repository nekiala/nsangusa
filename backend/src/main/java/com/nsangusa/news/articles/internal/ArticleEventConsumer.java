package com.nsangusa.news.articles.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ArticleEventConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final ArticleRepository articles;

  ArticleEventConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      ArticleRepository articles) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.articles = articles;
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "article-draft-v1")
  @Transactional
  void drafts(String json) {
    if (!"ArticleDraftGenerated".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleDraftGenerated.class);
    if (processed.wasProcessed(event.eventId(), "article-draft-v1")) {
      return;
    }
    UUID articleId = event.aggregateId();
    articles.save(Article.fromDraft(articleId, event.payload()));
    events.enqueue(
        "ArticleImageRequested",
        articleId,
        event.correlationId(),
        event.eventId(),
        "article-image-requested:" + articleId,
        new ArticleImageRequested(
            articleId, event.payload().imagePrompt(), event.payload().imageAltText()));
    processed.markProcessed(event.eventId(), "article-draft-v1");
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "article-image-result-v1")
  @Transactional
  void images(String json) {
    if (!"ArticleImageGenerated".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleImageGenerated.class);
    if (processed.wasProcessed(event.eventId(), "article-image-result-v1")) {
      return;
    }
    var article =
        articles
            .findById(event.payload().articleId())
            .orElseThrow(() -> new IllegalStateException("Article missing"));
    article.imageReady(event.payload().objectKey(), event.payload().altText());
    events.enqueue(
        "ArticleReadyForReview",
        article.id,
        event.correlationId(),
        event.eventId(),
        "article-ready-for-review:" + article.id,
        new ArticleReadyForReview(article.id));
    processed.markProcessed(event.eventId(), "article-image-result-v1");
  }
}
