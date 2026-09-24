package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class ReplayScopeInterceptorTests {
  private final ReplaySafetyRegistry safety = mock(ReplaySafetyRegistry.class);
  private final ReplayScopeInterceptor interceptor =
      new ReplayScopeInterceptor(safety, new ObjectMapper());

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
}
