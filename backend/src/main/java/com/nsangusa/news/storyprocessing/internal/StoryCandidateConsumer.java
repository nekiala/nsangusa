package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import java.util.List;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class StoryCandidateConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final StoryCandidateRepository stories;

  StoryCandidateConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      StoryCandidateRepository stories) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.stories = stories;
  }

  @KafkaListener(topics = EventTopics.INGESTION, groupId = "story-candidate-v1")
  @Transactional
  void consume(String json) {
    if (!"XPostNormalized".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, XPostNormalized.class);
    if (processed.wasProcessed(event.eventId(), "story-candidate-v1")) {
      return;
    }
    UUID storyId = UUID.randomUUID();
    String topic = event.payload().topics().stream().sorted().findFirst().orElse("general");
    stories.save(new StoryCandidate(storyId, event.payload().sourcePostId(), topic));
    var source =
        new SourceReference(
            event.payload().sourcePostId(),
            event.payload().handle(),
            event.payload().postId(),
            event.payload().canonicalUrl(),
            event.payload().publishedAt());
    events.enqueue(
        "StoryAnalysisRequested",
        storyId,
        event.correlationId(),
        event.eventId(),
        "story-analysis-requested:" + storyId,
        new StoryAnalysisRequested(storyId, List.of(source), event.payload().normalizedText()));
    processed.markProcessed(event.eventId(), "story-candidate-v1");
  }
}
