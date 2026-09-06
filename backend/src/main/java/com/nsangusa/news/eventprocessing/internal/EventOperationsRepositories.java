package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {
  Optional<FailedEvent> findByDltTopicAndDltPartitionAndDltOffset(
      String dltTopic, int dltPartition, long dltOffset);

  List<FailedEvent> findByStatusOrderByFailedAtDesc(String status, Pageable pageable);

  List<FailedEvent> findAllByOrderByFailedAtDesc(Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from FailedEvent f where f.id in :ids order by f.failedAt")
  List<FailedEvent> findLockedByIds(Collection<UUID> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<FailedEvent> findByFailedAtBetweenOrderByFailedAtAsc(
      Instant from, Instant to, Pageable pageable);
}

interface EventReplayRequestRepository extends JpaRepository<EventReplayRequest, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<EventReplayRequest> findByStatusInOrderByRequestedAtAsc(
      List<String> statuses, Pageable pageable);
}

interface EventReplayRecordRepository extends JpaRepository<EventReplayRecord, UUID> {
  boolean existsByReplayRequestIdAndFailedEventId(UUID replayRequestId, UUID failedEventId);

  List<EventReplayRecord> findByReplayRequestIdOrderByOccurredAtAsc(UUID replayRequestId);
}

interface ReplaySuppressionRepository extends JpaRepository<ReplaySuppression, UUID> {}

interface EventReplayItemRepository extends JpaRepository<EventReplayItem, UUID> {
  List<EventReplayItem> findByReplayRequestIdAndStatusOrderById(
      UUID replayRequestId, String status, Pageable pageable);

  long countByReplayRequestIdAndStatus(UUID replayRequestId, String status);
}
