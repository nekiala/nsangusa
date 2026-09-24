package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class StoryAnalysisScheduler {
  private final StoryCandidateRepository stories;
  private final StoryCandidateSourceRepository sources;
  private final DurableEventPublisher events;
  private final Duration quietPeriod;
  private final int batchSize;
  private final int maxSourceMaterialChars;

  StoryAnalysisScheduler(
      StoryCandidateRepository stories,
      StoryCandidateSourceRepository sources,
      DurableEventPublisher events,
      @Value("${news.story.quiet-period:PT2M}") Duration quietPeriod,
      @Value("${news.story.dispatch-batch-size:50}") int batchSize,
      @Value("${news.story.max-source-material-chars:40000}") int maxSourceMaterialChars) {
    this.stories = stories;
    this.sources = sources;
    this.events = events;
    this.quietPeriod = quietPeriod;
    this.batchSize = Math.max(1, Math.min(batchSize, 200));
    this.maxSourceMaterialChars = Math.max(1_000, maxSourceMaterialChars);
  }

  @Scheduled(fixedDelayString = "${news.story.dispatch-interval:30000}")
  @Transactional
  void dispatchReady() {
    Instant requestedAt = Instant.now();
    for (var story :
        stories.findByStatusAndLastSourceAtLessThanEqualOrderByLastSourceAtAsc(
            "collecting", requestedAt.minus(quietPeriod), PageRequest.of(0, batchSize))) {
      List<StoryCandidateSource> storySources =
          sources.findByStoryCandidateIdOrderByPublishedAtAsc(story.id);
      if (storySources.isEmpty()) {
        throw new IllegalStateException("Story candidate has no source material");
      }
      story.analysisRequested(requestedAt);
      events.enqueue(
          "StoryAnalysisRequested",
          story.id,
          story.correlationId,
          story.lastSourceEventId,
          "story-analysis-requested:" + story.id,
          new StoryAnalysisRequested(
              story.id,
              storySources.stream().map(StoryCandidateSource::reference).toList(),
              sourceMaterial(storySources)));
    }
  }

  private String sourceMaterial(List<StoryCandidateSource> storySources) {
    StringBuilder material = new StringBuilder();
    for (var source : storySources) {
      int separatorLength = material.isEmpty() ? 0 : 2;
      String header =
          "Source @" + source.account + " (" + source.url + ", " + source.publishedAt + "):\n";
      int remaining =
          maxSourceMaterialChars - material.length() - separatorLength - header.length();
      if (remaining <= 0) {
        break;
      }
      if (!material.isEmpty()) {
        material.append("\n\n");
      }
      material.append(header);
      material.append(
          source.normalizedText, 0, Math.min(source.normalizedText.length(), remaining));
    }
    if (material.isEmpty()) {
      throw new IllegalStateException("Story source material is empty");
    }
    return material.toString();
  }
}
