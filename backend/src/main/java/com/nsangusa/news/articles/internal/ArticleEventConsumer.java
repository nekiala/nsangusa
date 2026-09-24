package com.nsangusa.news.articles.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleImageCandidateGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.media.MediaService;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
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
  private final SourceIngestionService sources;
  private final MediaService media;

  ArticleEventConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      ArticleRepository articles,
      SourceIngestionService sources,
      MediaService media) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.articles = articles;
    this.sources = sources;
    this.media = media;
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
    sources.assertSourcesPublishable(
        event.payload().sources().stream().map(source -> source.sourcePostId()).toList());
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
    UUID generationId =
        event.payload().generationId() == null
            ? media.requireGenerationId(event.payload().articleId(), event.payload().objectKey())
            : event.payload().generationId();
    article.imageCandidateReady(generationId);
    processed.markProcessed(event.eventId(), "article-image-result-v1");
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "article-image-candidate-v2")
  @Transactional
  void imageCandidates(String json) {
    if (!"ArticleImageCandidateGenerated".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleImageCandidateGenerated.class);
    if (processed.wasProcessed(event.eventId(), "article-image-candidate-v2")) {
      return;
    }
    var article =
        articles
            .findById(event.payload().articleId())
            .orElseThrow(() -> new IllegalStateException("Article missing"));
    article.imageCandidateReady(event.payload().generationId());
    processed.markProcessed(event.eventId(), "article-image-candidate-v2");
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "article-image-approval-v1")
  @Transactional
  void approvedImages(String json) {
    if (!"ArticleImageApproved".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleImageApproved.class);
    if (processed.wasProcessed(event.eventId(), "article-image-approval-v1")) {
      return;
    }
    var article =
        articles
            .findById(event.payload().articleId())
            .orElseThrow(() -> new IllegalStateException("Article missing"));
    // Image approval commits its selected generation before the outbox event is delivered.
    if (article.approvedImageGenerationId != null
        && !article.approvedImageGenerationId.equals(event.payload().generationId())) {
      processed.markProcessed(event.eventId(), "article-image-approval-v1");
      return;
    }
    boolean readyForReview =
        article.imageApproved(
            event.payload().generationId(),
            event.payload().objectKey(),
            event.payload().altText(),
            !Boolean.FALSE.equals(event.payload().generatedImage()));
    if (readyForReview) {
      events.enqueue(
          "ArticleReadyForReview",
          article.id,
          event.correlationId(),
          event.eventId(),
          "article-ready-for-review:" + article.id,
          new ArticleReadyForReview(article.id));
    }
    processed.markProcessed(event.eventId(), "article-image-approval-v1");
  }
}
