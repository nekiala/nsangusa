package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.eventprocessing.TerminalEventException;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SourceNormalizationConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final SourcePostRepository posts;
  private final MonitoredXAccountRepository accounts;

  SourceNormalizationConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      SourcePostRepository posts,
      MonitoredXAccountRepository accounts) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.posts = posts;
    this.accounts = accounts;
  }

  @KafkaListener(topics = EventTopics.INGESTION, groupId = "source-normalizer-v1")
  @Transactional
  void consume(String json) {
    if (!"XPostDiscovered".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, XPostDiscovered.class);
    if (processed.wasProcessed(event.eventId(), "source-normalizer-v1")) {
      return;
    }
    var post =
        posts
            .findLockedById(event.aggregateId())
            .orElseThrow(() -> new IllegalStateException("Source missing"));
    if (!post.monitoredAccountId.equals(event.payload().monitoredAccountId())
        || !post.postId.equals(event.payload().postId())) {
      throw new TerminalEventException("Discovered event does not identify the stored source");
    }
    if (!"active".equals(post.status)) {
      processed.markProcessed(event.eventId(), "source-normalizer-v1");
      return;
    }
    String normalized = normalize(event.payload().permittedText());
    var account =
        accounts
            .findById(post.monitoredAccountId)
            .orElseThrow(() -> new IllegalStateException("Monitored account missing"));
    Set<String> hashtags =
        Arrays.stream(normalized.split("\\s+"))
            .filter(word -> word.startsWith("#") && word.length() > 1)
            .map(word -> word.substring(1).toLowerCase(Locale.ROOT))
            .limit(10)
            .collect(Collectors.toUnmodifiableSet());
    Set<String> configuredTopics =
        Arrays.stream(account.topics.split(","))
            .map(String::trim)
            .filter(topic -> !topic.isBlank())
            .map(topic -> topic.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    long matchedTopics =
        configuredTopics.stream()
            .filter(
                topic ->
                    hashtags.contains(topic) || normalized.toLowerCase(Locale.ROOT).contains(topic))
            .count();
    double relevance =
        configuredTopics.isEmpty() ? 1.0 : (double) matchedTopics / configuredTopics.size();
    if (relevance < account.relevanceThreshold) {
      post.status = "excluded_relevance";
      processed.markProcessed(event.eventId(), "source-normalizer-v1");
      return;
    }
    Set<String> topics =
        java.util.stream.Stream.concat(configuredTopics.stream(), hashtags.stream())
            .limit(20)
            .collect(Collectors.toUnmodifiableSet());
    if (topics.isEmpty()) {
      topics = Set.of("general");
    }
    var payload =
        new XPostNormalized(
            post.id,
            post.postId,
            post.handle,
            post.canonicalUrl,
            normalized,
            post.publishedAt,
            topics,
            post.conversationId);
    events.enqueue(
        "XPostNormalized",
        post.id,
        event.correlationId(),
        event.eventId(),
        "x-post-normalized:" + post.postId,
        payload);
    processed.markProcessed(event.eventId(), "source-normalizer-v1");
  }

  static String normalize(String value) {
    String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    if (normalized.length() > 10_000) {
      throw new IllegalArgumentException("Source content exceeds permitted size");
    }
    return normalized;
  }
}
