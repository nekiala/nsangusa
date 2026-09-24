package com.nsangusa.news.publication.internal;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PublicationScheduler {
  private static final Logger log = LoggerFactory.getLogger(PublicationScheduler.class);
  private final ScheduledPublicationRepository schedules;
  private final ScheduledPublicationExecutor executor;
  private final int batchSize;

  PublicationScheduler(
      ScheduledPublicationRepository schedules,
      ScheduledPublicationExecutor executor,
      @Value("${news.publication.schedule-batch-size:50}") int batchSize) {
    if (batchSize < 1 || batchSize > 100) {
      throw new IllegalArgumentException(
          "Publication schedule batch size must be between 1 and 100");
    }
    this.schedules = schedules;
    this.executor = executor;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${news.publication.schedule-poll-interval:10000}")
  void publishDue() {
    Instant cutoff = Instant.now();
    for (var id : schedules.dueIds(cutoff, PageRequest.of(0, batchSize))) {
      try {
        executor.execute(id, cutoff);
      } catch (RuntimeException failure) {
        log.error(
            "Could not record publication schedule {} outcome ({})",
            id,
            failure.getClass().getSimpleName());
      }
    }
  }
}
