package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.StoryCandidateCreated;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class StoryCandidateConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final StoryCandidateRepository stories;
  private final StoryCandidateSourceRepository sources;
  private final StoryClusterLock clusterLock;
  private final DurableEventPublisher events;
  private final Duration clusteringWindow;
  private final double similarityThreshold;
  private final int maxSources;

  StoryCandidateConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      StoryCandidateRepository stories,
      StoryCandidateSourceRepository sources,
      StoryClusterLock clusterLock,
      DurableEventPublisher events,
      @Value("${news.story.clustering-window:PT6H}") Duration clusteringWindow,
      @Value("${news.story.similarity-threshold:0.30}") double similarityThreshold,
      @Value("${news.story.max-sources:10}") int maxSources) {
    this.reader = reader;
    this.processed = processed;
    this.stories = stories;
    this.sources = sources;
    this.clusterLock = clusterLock;
    this.events = events;
    this.clusteringWindow = clusteringWindow;
    if (similarityThreshold < 0 || similarityThreshold > 1) {
      throw new IllegalArgumentException("Story similarity threshold must be between 0 and 1");
    }
    this.similarityThreshold = similarityThreshold;
    this.maxSources = Math.max(1, Math.min(maxSources, 50));
  }

  @KafkaListener(topics = EventTopics.INGESTION, groupId = "story-candidate-v1")
  @KafkaListener(
      topics = EventTopics.INGESTION_RETRY,
      groupId = "story-candidate-v1" + EventTopics.RETRY_GROUP_SUFFIX)
  @Transactional
  void consume(String json) {
    if (!"XPostNormalized".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, XPostNormalized.class);
    if (processed.wasProcessed(event.eventId(), "story-candidate-v1")) {
      return;
    }
    if (sources.existsBySourcePostId(event.payload().sourcePostId())) {
      processed.markProcessed(event.eventId(), "story-candidate-v1");
      return;
    }
    String topic = event.payload().topics().stream().sorted().findFirst().orElse("general");
    String conversationId = event.payload().conversationId();
    clusterLock.lock("topic:" + topic);
    if (conversationId != null) {
      clusterLock.lock("conversation:" + conversationId);
    }
    if (sources.existsBySourcePostId(event.payload().sourcePostId())) {
      processed.markProcessed(event.eventId(), "story-candidate-v1");
      return;
    }
    var terms = StoryClustering.terms(event.payload().normalizedText());
    java.time.Instant cutoff = java.time.Instant.now().minus(clusteringWindow);
    var conversationCandidates =
        conversationId == null
            ? java.util.stream.Stream.<StoryCandidate>empty()
            : stories
                .findCollectingByConversationId(conversationId, cutoff, PageRequest.of(0, 25))
                .stream();
    var similarityCandidates =
        stories.findCollecting(topic, cutoff, PageRequest.of(0, 25)).stream();
    var candidateIds =
        java.util.stream.Stream.concat(conversationCandidates, similarityCandidates)
            .map(candidate -> candidate.id)
            .distinct()
            .sorted()
            .toList();
    var lockedCandidates =
        candidateIds.isEmpty()
            ? java.util.List.<StoryCandidate>of()
            : stories.findLockedByIdIn(candidateIds);
    var existing =
        lockedCandidates.stream()
            .filter(
                story -> "collecting".equals(story.status) && !story.lastSourceAt.isBefore(cutoff))
            .filter(story -> story.sourceCount < maxSources)
            .filter(story -> story.matches(conversationId, terms, similarityThreshold))
            .sorted(
                java.util.Comparator.comparing(
                        (StoryCandidate story) ->
                            conversationId != null && conversationId.equals(story.conversationId))
                    .reversed()
                    .thenComparing(
                        story -> story.lastSourceAt, java.util.Comparator.reverseOrder()))
            .findFirst();
    if (existing.isPresent()) {
      var candidate = existing.orElseThrow();
      candidate.addSource(terms, event.eventId());
      sources.save(new StoryCandidateSource(candidate.id, event.payload()));
    } else {
      var candidate =
          stories.saveAndFlush(
              new StoryCandidate(
                  UUID.randomUUID(),
                  event.payload().sourcePostId(),
                  topic,
                  conversationId,
                  terms,
                  event.correlationId(),
                  event.eventId()));
      events.enqueue(
          "StoryCandidateCreated",
          candidate.id,
          event.correlationId(),
          event.eventId(),
          "story-candidate-created:" + candidate.id,
          new StoryCandidateCreated(
              candidate.id,
              candidate.primarySourcePostId,
              candidate.topic,
              candidate.conversationId,
              candidate.createdAt));
    }
    processed.markProcessed(event.eventId(), "story-candidate-v1");
  }
}
