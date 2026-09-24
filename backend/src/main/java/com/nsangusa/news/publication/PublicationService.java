package com.nsangusa.news.publication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface PublicationService {
  UUID schedule(UUID articleId, Instant publishAt, UUID editorId);

  /** Mutation replays return this current view, not a snapshot of the original HTTP response. */
  ScheduleView get(UUID scheduleId);

  SchedulePage list(String status, UUID articleId, int page, int size);

  ScheduleView reschedule(UUID scheduleId, long expectedVersion, Instant publishAt, UUID editorId);

  ScheduleView cancel(UUID scheduleId, long expectedVersion, UUID editorId);

  PolicyView policy();

  record SchedulePage(List<ScheduleView> items, int page, int size, long total) {}

  record ScheduleView(
      UUID id,
      UUID articleId,
      Instant publishAt,
      String status,
      long version,
      Long articleVersion,
      UUID scheduledBy,
      UUID updatedBy,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt,
      Instant lastAttemptAt,
      int attemptCount,
      String lastError) {}

  record PolicyView(
      String policy,
      BigDecimal confidenceThreshold,
      Map<String, BigDecimal> topicRules,
      Set<String> approvedSourceAccounts,
      boolean humanPublicationAllowed,
      String explanation) {}
}
