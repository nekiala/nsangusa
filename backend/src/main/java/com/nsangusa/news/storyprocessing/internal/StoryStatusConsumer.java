package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisBlocked;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class StoryStatusConsumer {
  private static final String CONSUMER = "story-status-v1";

  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final StoryCandidateRepository stories;

  StoryStatusConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      StoryCandidateRepository stories) {
    this.reader = reader;
    this.processed = processed;
    this.stories = stories;
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = CONSUMER)
  @Transactional
  void consume(String json) {
    String eventType = reader.eventType(json);
    if ("StoryAnalysisBlocked".equals(eventType)) {
      var event = reader.read(json, StoryAnalysisBlocked.class);
      if (processed.wasProcessed(event.eventId(), CONSUMER)) {
        return;
      }
      stories
          .findLockedById(event.payload().storyCandidateId())
          .orElseThrow(() -> new IllegalStateException("Story candidate missing"))
          .blockForSafety();
      processed.markProcessed(event.eventId(), CONSUMER);
    } else if ("ArticleDraftGenerated".equals(eventType)) {
      var event = reader.read(json, ArticleDraftGenerated.class);
      if (processed.wasProcessed(event.eventId(), CONSUMER)) {
        return;
      }
      var candidate =
          stories
              .findLockedById(event.payload().storyCandidateId())
              .orElseThrow(() -> new IllegalStateException("Story candidate missing"));
      if (candidate.markDrafted()) {
        candidate.draftArticleId = event.aggregateId();
      }
      processed.markProcessed(event.eventId(), CONSUMER);
    }
  }
}
