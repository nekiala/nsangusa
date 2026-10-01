package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.integration.SystemActors;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PublicationConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final ArticleService articles;
  private final PublicationPolicyEvaluator policy;

  PublicationConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      ArticleService articles,
      PublicationPolicyEvaluator policy) {
    this.reader = reader;
    this.processed = processed;
    this.articles = articles;
    this.policy = policy;
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "publication-policy-v1")
  @KafkaListener(
      topics = EventTopics.EDITORIAL_RETRY,
      groupId = "publication-policy-v1" + EventTopics.RETRY_GROUP_SUFFIX)
  @Transactional
  void ready(String json) {
    if (!"ArticleReadyForReview".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleReadyForReview.class);
    if (processed.wasProcessed(event.eventId(), "publication-policy-v1")) {
      return;
    }
    var article = articles.getLocked(event.payload().articleId());
    if (article.state() == ArticleState.AWAITING_REVIEW
        && policy.shouldAutomaticallyPublish(article)) {
      articles.approve(article.id(), SystemActors.AUTOMATION);
    }
    processed.markProcessed(event.eventId(), "publication-policy-v1");
  }

  @KafkaListener(topics = EventTopics.PUBLICATION, groupId = "publication-v1")
  @KafkaListener(
      topics = EventTopics.PUBLICATION_RETRY,
      groupId = "publication-v1" + EventTopics.RETRY_GROUP_SUFFIX)
  @Transactional
  void consume(String json) {
    if (!"ArticleApproved".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleApproved.class);
    if (processed.wasProcessed(event.eventId(), "publication-v1")) {
      return;
    }
    var article = articles.getLocked(event.payload().articleId());
    if (article.state() == ArticleState.APPROVED && policy.shouldAutomaticallyPublish(article)) {
      articles.publish(
          event.payload().articleId(),
          event.payload().approvedBy(),
          event.correlationId(),
          event.eventId());
    }
    processed.markProcessed(event.eventId(), "publication-v1");
  }
}
