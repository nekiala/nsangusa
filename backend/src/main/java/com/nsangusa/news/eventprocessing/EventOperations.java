package com.nsangusa.news.eventprocessing;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface EventOperations {
  List<FailedEventView> listFailed(String status, int limit);

  FailedEventPage failedPage(String status, int page, int size);

  ReplayPage replays(int page, int size);

  ReplayRequestView confirmReplay(UUID previewId, UUID actorId);

  ReplayRequestView requestReplay(ReplayCommand command);

  ReplayRequestView getReplay(UUID requestId);

  List<ReplayRecordView> listReplayRecords(UUID requestId);

  record FailedEventPage(List<FailedEventView> items, int page, int size, long total) {}

  record ReplayPage(List<ReplayRequestView> items, int page, int size, long total) {}

  record FailedEventView(
      UUID id,
      UUID eventId,
      String eventType,
      UUID aggregateId,
      String originalTopic,
      int originalPartition,
      long originalOffset,
      String consumerGroup,
      String exceptionClass,
      String exceptionMessage,
      int deliveryAttempt,
      boolean poisonMessage,
      String status,
      Instant failedAt,
      Instant lastUpdatedAt) {}

  record ReplayCommand(
      Set<UUID> failedEventIds,
      Instant failedFrom,
      Instant failedTo,
      int maximumMessages,
      int messagesPerSecond,
      boolean dryRun,
      boolean includePoison,
      UUID actorId,
      String reason,
      String auditMetadata) {}

  record ReplayRequestView(
      UUID id,
      UUID actorId,
      String reason,
      boolean dryRun,
      boolean includePoison,
      int messagesPerSecond,
      int candidateCount,
      int replayedCount,
      int blockedCount,
      String status,
      Instant requestedAt,
      Instant completedAt) {}

  record ReplayRecordView(
      UUID id,
      UUID failedEventId,
      UUID eventId,
      UUID aggregateId,
      String originalTopic,
      String outcome,
      String detail,
      Instant occurredAt) {}
}
