package com.nsangusa.news.storyprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import com.nsangusa.news.integration.NewsEvents.StoryCandidateCreated;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class StoryClusteringTests {
  @Test
  void relatedNormalizedPostsJoinTheSameCollectingStory() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var stories = mock(StoryCandidateRepository.class);
    var sources = mock(StoryCandidateSourceRepository.class);
    var clusterLock = mock(StoryClusterLock.class);
    var joinedEvents = mock(DurableEventPublisher.class);
    UUID storyId = UUID.randomUUID();
    var candidate =
        new StoryCandidate(
            storyId,
            UUID.randomUUID(),
            "economy",
            null,
            StoryClustering.terms("central bank raises interest rates amid inflation"),
            UUID.randomUUID(),
            UUID.randomUUID());
    var event =
        normalizedEvent(
            "central bank raises rates again as inflation remains high", Set.of("economy"), null);
    when(reader.eventType("event")).thenReturn("XPostNormalized");
    when(reader.read("event", XPostNormalized.class)).thenReturn(event);
    when(stories.findCollecting(eq("economy"), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(candidate));
    when(stories.findLockedByIdIn(List.of(storyId))).thenReturn(List.of(candidate));

    new StoryCandidateConsumer(
            reader,
            processed,
            stories,
            sources,
            clusterLock,
            joinedEvents,
            Duration.ofHours(6),
            0.25,
            10)
        .consume("event");
    verifyNoInteractions(joinedEvents);

    var captured = ArgumentCaptor.forClass(StoryCandidateSource.class);
    verify(sources).save(captured.capture());
    assertThat(captured.getValue().storyCandidateId).isEqualTo(storyId);
    assertThat(candidate.sourceCount).isEqualTo(2);
    verify(clusterLock).lock("topic:economy");
    verify(processed).markProcessed(event.eventId(), "story-candidate-v1");
  }

  @Test
  void conversationMatchClustersAcrossDifferentTopics() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var stories = mock(StoryCandidateRepository.class);
    var sources = mock(StoryCandidateSourceRepository.class);
    var clusterLock = mock(StoryClusterLock.class);
    var joinedEvents = mock(DurableEventPublisher.class);
    String conversationId = "1900000000000000000";
    var candidate =
        new StoryCandidate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "politics",
            conversationId,
            Set.of("election"),
            UUID.randomUUID(),
            UUID.randomUUID());
    var event = normalizedEvent("Unrelated wording", Set.of("breaking"), conversationId);
    when(reader.eventType("event")).thenReturn("XPostNormalized");
    when(reader.read("event", XPostNormalized.class)).thenReturn(event);
    when(stories.findCollectingByConversationId(
            eq(conversationId), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(candidate));
    when(stories.findCollecting(eq("breaking"), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(stories.findLockedByIdIn(List.of(candidate.id))).thenReturn(List.of(candidate));

    new StoryCandidateConsumer(
            reader,
            processed,
            stories,
            sources,
            clusterLock,
            joinedEvents,
            Duration.ofHours(6),
            0.9,
            10)
        .consume("event");
    verifyNoInteractions(joinedEvents);

    assertThat(candidate.sourceCount).isEqualTo(2);
    var orderedLocks = inOrder(clusterLock);
    orderedLocks.verify(clusterLock).lock("topic:breaking");
    orderedLocks.verify(clusterLock).lock("conversation:" + conversationId);
  }

  @Test
  void unmatchedPostCreatesACandidateAndPublishesStoryCandidateCreated() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var stories = mock(StoryCandidateRepository.class);
    var events = mock(DurableEventPublisher.class);
    var event = normalizedEvent("Port strike halts container traffic", Set.of("economy"), null);
    when(reader.eventType("event")).thenReturn("XPostNormalized");
    when(reader.read("event", XPostNormalized.class)).thenReturn(event);
    when(stories.findCollecting(eq("economy"), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(stories.saveAndFlush(any(StoryCandidate.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    new StoryCandidateConsumer(
            reader,
            processed,
            stories,
            mock(StoryCandidateSourceRepository.class),
            mock(StoryClusterLock.class),
            events,
            Duration.ofHours(6),
            0.25,
            10)
        .consume("event");

    var saved = ArgumentCaptor.forClass(StoryCandidate.class);
    verify(stories).saveAndFlush(saved.capture());
    var payload = ArgumentCaptor.forClass(StoryCandidateCreated.class);
    verify(events)
        .enqueue(
            eq("StoryCandidateCreated"),
            eq(saved.getValue().id),
            eq(event.correlationId()),
            eq(event.eventId()),
            eq("story-candidate-created:" + saved.getValue().id),
            payload.capture());
    assertThat(payload.getValue().primarySourcePostId()).isEqualTo(event.payload().sourcePostId());
    assertThat(payload.getValue().topic()).isEqualTo("economy");
    verify(processed).markProcessed(event.eventId(), "story-candidate-v1");
  }

  @Test
  void dispatcherEmitsAllClusteredSourcesAfterQuietPeriod() {
    var stories = mock(StoryCandidateRepository.class);
    var sources = mock(StoryCandidateSourceRepository.class);
    var events = mock(DurableEventPublisher.class);
    UUID storyId = UUID.randomUUID();
    var candidate =
        new StoryCandidate(
            storyId,
            UUID.randomUUID(),
            "world",
            "1900000000000000000",
            Set.of("election", "results"),
            UUID.randomUUID(),
            UUID.randomUUID());
    var first =
        new StoryCandidateSource(
            storyId,
            normalizedEvent("Election results announced", Set.of("world"), null).payload());
    var second =
        new StoryCandidateSource(
            storyId,
            normalizedEvent("Officials confirm election results", Set.of("world"), null).payload());
    when(stories.findByStatusAndLastSourceAtLessThanEqualOrderByLastSourceAtAsc(
            eq("collecting"), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(candidate));
    when(sources.findByStoryCandidateIdOrderByPublishedAtAsc(storyId))
        .thenReturn(List.of(first, second));

    new StoryAnalysisScheduler(stories, sources, events, Duration.ZERO, 50, 40_000).dispatchReady();

    var payload = ArgumentCaptor.forClass(StoryAnalysisRequested.class);
    verify(events)
        .enqueue(
            eq("StoryAnalysisRequested"),
            eq(storyId),
            eq(candidate.correlationId),
            eq(candidate.lastSourceEventId),
            eq("story-analysis-requested:" + storyId),
            payload.capture());
    assertThat(payload.getValue().sources()).hasSize(2);
    assertThat(payload.getValue().sourceMaterial())
        .contains("Election results announced", "Officials confirm election results");
    assertThat(candidate.status).isEqualTo("analyzing");
  }

  @Test
  void lateEditorialEventsCannotOverwriteComplianceExclusion() {
    var candidate =
        new StoryCandidate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "world",
            null,
            Set.of("report"),
            UUID.randomUUID(),
            UUID.randomUUID());
    candidate.status = "excluded_compliance";

    assertThat(candidate.blockForSafety()).isFalse();
    assertThat(candidate.markDrafted()).isFalse();
    assertThat(candidate.status).isEqualTo("excluded_compliance");
  }

  private static EventEnvelope<XPostNormalized> normalizedEvent(
      String text, Set<String> topics, String conversationId) {
    UUID sourceId = UUID.randomUUID();
    var payload =
        new XPostNormalized(
            sourceId,
            String.valueOf(Math.abs(sourceId.getMostSignificantBits())),
            "account",
            "https://x.com/account/status/1900000000000000000",
            text,
            Instant.now(),
            topics,
            conversationId);
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "XPostNormalized",
        1,
        sourceId,
        sourceId,
        null,
        Instant.now(),
        "test",
        Map.of(),
        "normalized:" + sourceId,
        payload);
  }
}
