package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.eventprocessing.EventOperations;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EventOperationsService implements EventOperations, ReplaySafetyRegistry {
  private static final int MAX_REPLAY_MESSAGES = 100;
  private static final int MAX_REPLAY_RATE = 20;
  private static final Duration MAX_REPLAY_RANGE = Duration.ofHours(24);

  private final FailedEventRepository failedEvents;
  private final EventReplayRequestRepository requests;
  private final EventReplayItemRepository items;
  private final EventReplayRecordRepository records;
  private final ReplaySuppressionRepository suppressions;
  private final KafkaTemplate<String, String> kafkaTemplate;

  EventOperationsService(
      FailedEventRepository failedEvents,
      EventReplayRequestRepository requests,
      EventReplayItemRepository items,
      EventReplayRecordRepository records,
      ReplaySuppressionRepository suppressions,
      KafkaTemplate<String, String> kafkaTemplate) {
    this.failedEvents = failedEvents;
    this.requests = requests;
    this.items = items;
    this.records = records;
    this.suppressions = suppressions;
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  @Transactional(readOnly = true)
  public List<FailedEventView> listFailed(String status, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 200));
    var page = PageRequest.of(0, boundedLimit);
    var failures =
        status == null || status.isBlank()
            ? failedEvents.findAllByOrderByFailedAtDesc(page)
            : failedEvents.findByStatusOrderByFailedAtDesc(status, page);
    return failures.stream().map(EventOperationsService::toView).toList();
  }

  @Override
  @Transactional
  public ReplayRequestView requestReplay(ReplayCommand command) {
    validate(command);
    List<FailedEvent> candidates = selectCandidates(command);
    var request =
        requests.save(
            new EventReplayRequest(
                command.actorId(),
                command.reason().trim(),
                command.dryRun(),
                command.includePoison(),
                command.messagesPerSecond(),
                candidates.size(),
                command.failedFrom(),
                command.failedTo(),
                command.auditMetadata()));
    for (var failure : candidates) {
      if (command.dryRun()) {
        records.save(new EventReplayRecord(request, failure, "dry_run", "eligible"));
      } else {
        failure.status = "replay_pending";
        failure.lastUpdatedAt = Instant.now();
        items.save(new EventReplayItem(request.id, failure.id, "pending"));
      }
    }
    return toView(request);
  }

  @Override
  @Transactional(readOnly = true)
  public ReplayRequestView getReplay(UUID requestId) {
    return toView(
        requests
            .findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown replay request")));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ReplayRecordView> listReplayRecords(UUID requestId) {
    if (!requests.existsById(requestId)) {
      throw new IllegalArgumentException("Unknown replay request");
    }
    return records.findByReplayRequestIdOrderByOccurredAtAsc(requestId).stream()
        .map(
            record ->
                new ReplayRecordView(
                    record.id,
                    record.failedEventId,
                    record.eventId,
                    record.aggregateId,
                    record.originalTopic,
                    record.outcome,
                    record.detail,
                    record.occurredAt))
        .toList();
  }

  @Override
  @Transactional
  public void suppressAggregate(UUID aggregateId, String reason, UUID actorId) {
    var suppression =
        suppressions
            .findById(aggregateId)
            .orElseGet(() -> new ReplaySuppression(aggregateId, reason, actorId));
    suppression.reason = reason;
    suppression.actorId = actorId;
    suppression.suppressedAt = Instant.now();
    suppressions.save(suppression);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isSuppressed(UUID aggregateId) {
    return aggregateId != null && suppressions.existsById(aggregateId);
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  void processReplayRequests() {
    var ready =
        requests.findByStatusInOrderByRequestedAtAsc(
            List.of("pending", "processing"), PageRequest.of(0, 1));
    if (ready.isEmpty()) {
      return;
    }
    var request = ready.getFirst();
    request.status = "processing";
    var batch =
        items.findByReplayRequestIdAndStatusOrderById(
            request.id, "pending", PageRequest.of(0, request.messagesPerSecond));
    for (var item : batch) {
      replay(request, item);
    }
    if (items.countByReplayRequestIdAndStatus(request.id, "pending") == 0) {
      request.status = request.blockedCount > 0 ? "completed_with_blocks" : "completed";
      request.completedAt = Instant.now();
    }
  }

  private void replay(EventReplayRequest request, EventReplayItem item) {
    var failure =
        failedEvents
            .findById(item.failedEventId)
            .orElseThrow(() -> new IllegalStateException("Replay failure record disappeared"));
    if (failure.aggregateId != null && suppressions.existsById(failure.aggregateId)) {
      item.status = "blocked";
      failure.status = "suppressed";
      failure.lastUpdatedAt = Instant.now();
      request.blockedCount++;
      records.save(
          new EventReplayRecord(request, failure, "blocked", "aggregate is compliance-suppressed"));
      return;
    }
    try {
      String key =
          failure.aggregateId != null
              ? failure.aggregateId.toString()
              : failure.eventId != null ? failure.eventId.toString() : failure.id.toString();
      var record = new ProducerRecord<String, String>(failure.originalTopic, key, failure.payload);
      record
          .headers()
          .add("x-replay-request-id", request.id.toString().getBytes(StandardCharsets.UTF_8))
          .add("x-original-dlt-id", failure.id.toString().getBytes(StandardCharsets.UTF_8))
          .add("x-replayed-by", request.actorId.toString().getBytes(StandardCharsets.UTF_8));
      kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
      item.status = "replayed";
      failure.status = "replayed";
      failure.lastUpdatedAt = Instant.now();
      request.replayedCount++;
      records.save(new EventReplayRecord(request, failure, "replayed", null));
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      item.status = "failed";
      failure.status = "replay_failed";
      failure.exceptionMessage = safeMessage(exception);
      failure.lastUpdatedAt = Instant.now();
      request.blockedCount++;
      records.save(new EventReplayRecord(request, failure, "failed", safeMessage(exception)));
    }
  }

  private List<FailedEvent> selectCandidates(ReplayCommand command) {
    List<FailedEvent> selected;
    if (command.failedEventIds() != null && !command.failedEventIds().isEmpty()) {
      selected =
          new ArrayList<>(
              failedEvents.findLockedByIds(new LinkedHashSet<>(command.failedEventIds())));
      selected.sort(java.util.Comparator.comparing(event -> event.failedAt));
    } else {
      selected =
          failedEvents.findByFailedAtBetweenOrderByFailedAtAsc(
              command.failedFrom(),
              command.failedTo(),
              PageRequest.of(0, command.maximumMessages()));
    }
    return selected.stream()
        .filter(
            event ->
                "eligible".equals(event.status)
                    || (command.includePoison() && "poison".equals(event.status)))
        .limit(command.maximumMessages())
        .toList();
  }

  private static void validate(ReplayCommand command) {
    if (command.actorId() == null) {
      throw new IllegalArgumentException("Replay actor is required");
    }
    if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 500) {
      throw new IllegalArgumentException("Replay reason is required and limited to 500 characters");
    }
    if (command.auditMetadata() == null || command.auditMetadata().length() > 2_000) {
      throw new IllegalArgumentException("Replay audit metadata is required and limited");
    }
    if (command.maximumMessages() < 1 || command.maximumMessages() > MAX_REPLAY_MESSAGES) {
      throw new IllegalArgumentException("maximumMessages must be between 1 and 100");
    }
    if (command.messagesPerSecond() < 1 || command.messagesPerSecond() > MAX_REPLAY_RATE) {
      throw new IllegalArgumentException("messagesPerSecond must be between 1 and 20");
    }
    boolean ids = command.failedEventIds() != null && !command.failedEventIds().isEmpty();
    boolean range = command.failedFrom() != null || command.failedTo() != null;
    if (ids == range) {
      throw new IllegalArgumentException("Specify either failedEventIds or a complete time range");
    }
    if (ids && command.failedEventIds().size() > MAX_REPLAY_MESSAGES) {
      throw new IllegalArgumentException("At most 100 failedEventIds may be replayed");
    }
    if (range) {
      if (command.failedFrom() == null
          || command.failedTo() == null
          || !command.failedTo().isAfter(command.failedFrom())) {
        throw new IllegalArgumentException("A valid failedFrom/failedTo range is required");
      }
      if (Duration.between(command.failedFrom(), command.failedTo()).compareTo(MAX_REPLAY_RANGE)
          > 0) {
        throw new IllegalArgumentException("Replay time range cannot exceed 24 hours");
      }
      if (command.failedTo().isAfter(Instant.now().plusSeconds(300))) {
        throw new IllegalArgumentException("Replay time range cannot extend into the future");
      }
    }
  }

  private static FailedEventView toView(FailedEvent event) {
    return new FailedEventView(
        event.id,
        event.eventId,
        event.eventType,
        event.aggregateId,
        event.originalTopic,
        event.originalPartition,
        event.originalOffset,
        event.consumerGroup,
        event.exceptionClass,
        event.exceptionMessage,
        event.deliveryAttempt,
        event.poisonMessage,
        event.status,
        event.failedAt,
        event.lastUpdatedAt);
  }

  private static ReplayRequestView toView(EventReplayRequest request) {
    return new ReplayRequestView(
        request.id,
        request.actorId,
        request.reason,
        request.dryRun,
        request.includePoison,
        request.messagesPerSecond,
        request.candidateCount,
        request.replayedCount,
        request.blockedCount,
        request.status,
        request.requestedAt,
        request.completedAt);
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null
        ? exception.getClass().getSimpleName()
        : message.substring(0, Math.min(message.length(), 2_000));
  }
}
