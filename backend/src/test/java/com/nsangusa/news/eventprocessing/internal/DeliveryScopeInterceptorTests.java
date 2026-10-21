package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class DeliveryScopeInterceptorTests {
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  private final ReplaySafetyRegistry safety = mock(ReplaySafetyRegistry.class);
  private final MovingClock clock = new MovingClock();
  private final List<Long> sleeps = new ArrayList<>();
  private final DeliveryScopeInterceptor interceptor =
      new DeliveryScopeInterceptor(
          safety,
          new ObjectMapper(),
          Duration.ofSeconds(120),
          clock,
          milliseconds -> {
            sleeps.add(milliseconds);
            clock.advance(Duration.ofMillis(milliseconds));
          });

  @Test
  void normalInputIsNotFilteredAndMetadataNeverLosesReplayedFailures() {
    var normal = record("news.editorial.v1", UUID.randomUUID(), null);
    assertThat(interceptor.intercept(normal, consumer("other"))).isSameAs(normal);
    var dlt = record("news.editorial.v1.dlt", UUID.randomUUID(), "target");
    assertThat(interceptor.intercept(dlt, consumer("failure-metadata-v1"))).isSameAs(dlt);
    verifyNoInteractions(safety);
  }

  @Test
  void onlyTheTargetGroupSeesAReplayAndSuppressionIsCheckedAgainAtDelivery() {
    UUID aggregateId = UUID.randomUUID();
    var replay = record("news.editorial.v1", aggregateId, "target");
    assertThat(interceptor.intercept(replay, consumer("other"))).isNull();
    verifyNoInteractions(safety);
    assertThat(interceptor.intercept(replay, consumer("target"))).isSameAs(replay);
    when(safety.isSuppressed(aggregateId)).thenReturn(true);
    assertThat(interceptor.intercept(replay, consumer("target"))).isNull();
  }

  @Test
  void malformedPoisonReplayStillReachesTheTargetListenerForValidation() {
    var replay = new ConsumerRecord<Object, Object>("news.editorial.v1", 0, 0, "key", "not-json");
    replay
        .headers()
        .add(EventFailurePolicy.REPLAY_GROUP_HEADER, "target".getBytes(StandardCharsets.UTF_8));
    assertThat(interceptor.intercept(replay, consumer("target"))).isSameAs(replay);
  }

  @Test
  void onlyTheFailedGroupsRetryTwinReceivesARetryAfterItsDelay() {
    var retry = retry(UUID.randomUUID(), "target", NOW.plusSeconds(30));
    assertThat(interceptor.intercept(retry, consumer("other-retry"))).isNull();
    assertThat(interceptor.intercept(retry, consumer("target"))).isNull();
    assertThat(sleeps).isEmpty();

    assertThat(interceptor.intercept(retry, consumer("target-retry"))).isSameAs(retry);
    assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(30));
    assertThat(sleeps).allMatch(sleep -> sleep <= 250);
  }

  @Test
  void unaddressedRetriesAreSkippedAndDueRetriesAreNotDelayed() {
    var unaddressed = retry(UUID.randomUUID(), null, NOW);
    assertThat(interceptor.intercept(unaddressed, consumer("target-retry"))).isNull();
    var due = retry(UUID.randomUUID(), "target", NOW.minusSeconds(5));
    assertThat(interceptor.intercept(due, consumer("target-retry"))).isSameAs(due);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void farFutureRetryWaitsNoLongerThanTheLongestConfiguredDelay() {
    var forged = retry(UUID.randomUUID(), "target", NOW.plus(Duration.ofDays(365)));
    assertThat(interceptor.intercept(forged, consumer("target-retry"))).isSameAs(forged);
    assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(120));
  }

  @Test
  void suppressedAggregatesAreNotRetriedAndShutdownStopsWaiting() {
    UUID aggregateId = UUID.randomUUID();
    when(safety.isSuppressed(aggregateId)).thenReturn(true);
    assertThat(interceptor.intercept(retry(aggregateId, "target", NOW), consumer("target-retry")))
        .isNull();

    interceptor.onApplicationEvent(null);
    var waiting = retry(UUID.randomUUID(), "target", NOW.plusSeconds(60));
    assertThat(interceptor.intercept(waiting, consumer("target-retry"))).isSameAs(waiting);
    assertThat(sleeps).isEmpty();
  }

  private static ConsumerRecord<Object, Object> retry(
      UUID aggregateId, String group, Instant notBefore) {
    var record = record("news.editorial.v1.retry", aggregateId, null);
    if (group != null) {
      record.headers().add(DelayedRetryPolicy.GROUP_HEADER, group.getBytes(StandardCharsets.UTF_8));
    }
    record
        .headers()
        .add(
            DelayedRetryPolicy.NOT_BEFORE_HEADER,
            ByteBuffer.allocate(Long.BYTES).putLong(notBefore.toEpochMilli()).array());
    return record;
  }

  private static ConsumerRecord<Object, Object> record(
      String topic, UUID aggregateId, String target) {
    var record =
        new ConsumerRecord<Object, Object>(
            topic, 0, 0, "key", "{\"aggregateId\":\"" + aggregateId + "\"}");
    if (target != null) {
      record
          .headers()
          .add(EventFailurePolicy.REPLAY_GROUP_HEADER, target.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }

  @SuppressWarnings("unchecked")
  private static Consumer<Object, Object> consumer(String group) {
    var consumer = mock(Consumer.class);
    when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata(group));
    return consumer;
  }

  private static final class MovingClock extends Clock {
    private Instant now = NOW;

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
