package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.EventOperations;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class EventOperationsServiceTests {
  @Mock FailedEventRepository failedEvents;
  @Mock EventReplayRequestRepository requests;
  @Mock EventReplayItemRepository items;
  @Mock EventReplayRecordRepository records;
  @Mock ReplaySuppressionRepository suppressions;
  @Mock KafkaTemplate<String, String> kafkaTemplate;

  private EventOperationsService service;

  @BeforeEach
  void setUp() {
    service =
        new EventOperationsService(
            failedEvents, requests, items, records, suppressions, kafkaTemplate);
  }

  @Test
  void dryRunPersistsAuditRequestAndRecordsWithoutPublishing() {
    var failure = failure(false);
    when(failedEvents.findLockedByIds(any())).thenReturn(List.of(failure));
    when(requests.save(any(EventReplayRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var command =
        new EventOperations.ReplayCommand(
            Set.of(failure.id),
            null,
            null,
            10,
            5,
            true,
            false,
            UUID.randomUUID(),
            "operator verification",
            "remoteAddress=127.0.0.1");

    var result = service.requestReplay(command);

    assertThat(result.status()).isEqualTo("dry_run_complete");
    assertThat(result.candidateCount()).isEqualTo(1);
    verify(records).save(any(EventReplayRecord.class));
  }

  @Test
  void replayWorkerBlocksComplianceSuppressedAggregates() {
    var failure = failure(false);
    failure.status = "replay_pending";
    var request =
        new EventReplayRequest(
            UUID.randomUUID(), "reviewed replay", false, false, 5, 1, null, null, "audit");
    var item = new EventReplayItem(request.id, failure.id, "pending");
    when(requests.findByStatusInOrderByRequestedAtAsc(any(), any(Pageable.class)))
        .thenReturn(List.of(request));
    when(items.findByReplayRequestIdAndStatusOrderById(
            request.id, "pending", org.springframework.data.domain.PageRequest.of(0, 5)))
        .thenReturn(List.of(item));
    when(failedEvents.findById(failure.id)).thenReturn(java.util.Optional.of(failure));
    when(suppressions.existsById(failure.aggregateId)).thenReturn(true);
    when(items.countByReplayRequestIdAndStatus(request.id, "pending")).thenReturn(0L);

    Instant started = Instant.now();
    service.processReplayRequests();

    assertThat(item.status).isEqualTo("blocked");
    assertThat(failure.status).isEqualTo("suppressed");
    assertThat(request.status).isEqualTo("completed_with_blocks");
    assertThat(request.nextReplayAt).isAfterOrEqualTo(started.plusSeconds(1));
    verify(records).save(any(EventReplayRecord.class));
  }

  @Test
  void rejectsUnboundedReplayRange() {
    Instant from = Instant.now().minusSeconds(25 * 60 * 60);
    var command =
        new EventOperations.ReplayCommand(
            Set.of(),
            from,
            Instant.now(),
            100,
            20,
            false,
            false,
            UUID.randomUUID(),
            "too broad",
            "audit");

    assertThatThrownBy(() -> service.requestReplay(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replay time range cannot exceed 24 hours");
  }

  @Test
  void aFailedReplayRequiresANewPreviewAndKeepsPoisonOptIn() {
    var failure = failure(false);
    failure.status = "replay_failed";
    when(failedEvents.findLockedByIds(any())).thenReturn(List.of(failure));
    when(requests.save(any(EventReplayRequest.class))).thenAnswer(call -> call.getArgument(0));
    var command =
        new EventOperations.ReplayCommand(
            Set.of(failure.id),
            null,
            null,
            10,
            1,
            true,
            false,
            UUID.randomUUID(),
            "Recovered broker",
            "audit");
    assertThat(service.requestReplay(command).candidateCount()).isEqualTo(1);
    failure.poisonMessage = true;
    assertThat(service.requestReplay(command).candidateCount()).isZero();
  }

  @Test
  void unscopedLegacyFailuresAreBlockedRatherThanBroadcastToEveryGroup() {
    var failure = failure(false);
    failure.consumerGroup = null;
    when(failedEvents.findLockedByIds(any())).thenReturn(List.of(failure));
    when(requests.save(any(EventReplayRequest.class))).thenAnswer(call -> call.getArgument(0));
    var command =
        new EventOperations.ReplayCommand(
            Set.of(failure.id),
            null,
            null,
            1,
            1,
            true,
            false,
            UUID.randomUUID(),
            "Review legacy failure",
            "audit");

    assertThat(service.requestReplay(command).blockedCount()).isEqualTo(1);
    verifyNoInteractions(kafkaTemplate);
  }

  @Test
  void replayPreservesScopeAndOriginalCoordinatesAndRedactsPublishFailureMessages() {
    var failure = failure(false);
    var request =
        new EventReplayRequest(
            UUID.randomUUID(), "reviewed replay", false, false, 1, 1, null, null, "audit");
    var item = new EventReplayItem(request.id, failure.id, "pending");
    when(requests.findByStatusInOrderByRequestedAtAsc(any(), any(Pageable.class)))
        .thenReturn(List.of(request));
    when(items.findByReplayRequestIdAndStatusOrderById(
            request.id, "pending", org.springframework.data.domain.PageRequest.of(0, 1)))
        .thenReturn(List.of(item));
    when(failedEvents.findById(failure.id)).thenReturn(java.util.Optional.of(failure));
    when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("broker credential=secret")));

    service.processReplayRequests();

    var captor =
        org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
    verify(kafkaTemplate).send(captor.capture());
    var sent = captor.getValue();
    assertThat(sent.topic()).isEqualTo(failure.originalTopic);
    assertThat(sent.partition()).isEqualTo(failure.originalPartition);
    assertThat(
            new String(
                sent.headers().lastHeader(EventFailurePolicy.REPLAY_GROUP_HEADER).value(),
                java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo(failure.consumerGroup);
    assertThat(
            java.nio.ByteBuffer.wrap(
                    sent.headers()
                        .lastHeader(
                            org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_OFFSET)
                        .value())
                .getLong())
        .isEqualTo(failure.originalOffset);
    assertThat(failure.exceptionMessage).doesNotContain("credential", "secret");
    assertThat(failure.status).isEqualTo("replay_failed");
  }

  private static FailedEvent failure(boolean poison) {
    return new FailedEvent(
        UUID.randomUUID(),
        "XPostDiscovered",
        UUID.randomUUID(),
        "news.ingestion.v1.dlt",
        0,
        10,
        "news.ingestion.v1",
        0,
        9,
        "source-normalizer-v1",
        "{\"eventType\":\"XPostDiscovered\"}",
        poison ? "java.lang.IllegalArgumentException" : "java.lang.IllegalStateException",
        "failure",
        4,
        poison);
  }
}
