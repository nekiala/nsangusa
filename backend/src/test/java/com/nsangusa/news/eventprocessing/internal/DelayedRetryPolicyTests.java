package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsangusa.news.eventprocessing.TerminalEventException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class DelayedRetryPolicyTests {
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private final DelayedRetryPolicy policy =
      new DelayedRetryPolicy(
          List.of(Duration.ofSeconds(30), Duration.ofSeconds(120)),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void exhaustedTransientMainFailureGoesToTheRetryTopicAddressedToTheFailedGroup() {
    var record = record("news.editorial.v1", 2);
    stamp(record, EventFailurePolicy.ATTEMPT_HEADER, 4);
    var failure = failure("article-draft-v1", new IllegalStateException("transient"));

    assertThat(policy.destination(record, failure))
        .isEqualTo(new TopicPartition("news.editorial.v1.retry", 2));
    var headers = policy.headers(record, failure);
    assertThat(text(headers, DelayedRetryPolicy.GROUP_HEADER)).isEqualTo("article-draft-v1");
    assertThat(integer(headers, DelayedRetryPolicy.ATTEMPT_HEADER)).isEqualTo(1);
    assertThat(integer(headers, DelayedRetryPolicy.PRIOR_DELIVERIES_HEADER)).isEqualTo(4);
    assertThat(instant(headers)).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void retryTwinFailuresAdvanceThroughEachDelayThenDeadLetterOnTheBaseTopic() {
    var failure = failure("article-draft-v1-retry", new IllegalStateException("transient"));
    var first = retryRecord(1, 4);
    assertThat(policy.destination(first, failure))
        .isEqualTo(new TopicPartition("news.editorial.v1.retry", 2));
    var headers = policy.headers(first, failure);
    assertThat(text(headers, DelayedRetryPolicy.GROUP_HEADER)).isEqualTo("article-draft-v1");
    assertThat(integer(headers, DelayedRetryPolicy.ATTEMPT_HEADER)).isEqualTo(2);
    assertThat(integer(headers, DelayedRetryPolicy.PRIOR_DELIVERIES_HEADER)).isEqualTo(5);
    assertThat(instant(headers)).isEqualTo(NOW.plusSeconds(120));

    var last = retryRecord(2, 5);
    assertThat(policy.destination(last, failure))
        .isEqualTo(new TopicPartition("news.editorial.v1.dlt", 2));
    var dltHeaders = policy.headers(last, failure);
    assertThat(dltHeaders.lastHeader(DelayedRetryPolicy.GROUP_HEADER)).isNull();
    assertThat(integer(dltHeaders, EventFailurePolicy.ATTEMPT_HEADER)).isEqualTo(6);
  }

  @Test
  void terminalAndUnscopedFailuresSkipTheRetryTier() {
    var record = record("news.editorial.v1", 0);
    for (Exception terminal :
        List.<Exception>of(
            new TerminalEventException("invariant"), new IllegalArgumentException("contract"))) {
      assertThat(policy.destination(record, failure("article-draft-v1", terminal)).topic())
          .isEqualTo("news.editorial.v1.dlt");
    }
    // Without a group a retry would reach every consumer group sharing the topic.
    assertThat(policy.destination(record, new IllegalStateException("no group")).topic())
        .isEqualTo("news.editorial.v1.dlt");
  }

  @Test
  void staleRetryHeadersOnAMainTopicRecordAreIgnored() {
    var replayed = record("news.editorial.v1", 0);
    stamp(replayed, DelayedRetryPolicy.ATTEMPT_HEADER, 2);
    var failure = failure("article-draft-v1", new IllegalStateException("transient"));
    assertThat(policy.destination(replayed, failure).topic()).isEqualTo("news.editorial.v1.retry");
    assertThat(integer(policy.headers(replayed, failure), DelayedRetryPolicy.ATTEMPT_HEADER))
        .isEqualTo(1);
  }

  @Test
  void emptyDelaysDisableTheRetryTierAndUnsafeDelaysAreRejected() {
    var disabled = new DelayedRetryPolicy(List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(
            disabled
                .destination(
                    record("news.editorial.v1", 0),
                    failure("article-draft-v1", new IllegalStateException("transient")))
                .topic())
        .isEqualTo("news.editorial.v1.dlt");
    for (var delays :
        List.of(
            List.of(Duration.ofMillis(500)),
            List.of(Duration.ofMinutes(5)),
            List.of(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)))) {
      assertThatThrownBy(() -> new DelayedRetryPolicy(delays, Clock.systemUTC()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void namingHelpersMapRetryTopicsAndTwinGroups() {
    assertThat(DelayedRetryPolicy.baseTopic("news.editorial.v1.retry"))
        .isEqualTo("news.editorial.v1");
    assertThat(DelayedRetryPolicy.baseTopic("news.editorial.v1")).isEqualTo("news.editorial.v1");
    assertThat(DelayedRetryPolicy.retryTwinOf("article-draft-v1-retry"))
        .isEqualTo("article-draft-v1");
    assertThat(DelayedRetryPolicy.retryTwinOf("article-draft-v1")).isNull();
  }

  private static ConsumerRecord<Object, Object> retryRecord(int attempt, int prior) {
    var record = record("news.editorial.v1.retry", 2);
    record
        .headers()
        .add(DelayedRetryPolicy.GROUP_HEADER, "article-draft-v1".getBytes(StandardCharsets.UTF_8));
    stamp(record, DelayedRetryPolicy.ATTEMPT_HEADER, attempt);
    stamp(record, DelayedRetryPolicy.PRIOR_DELIVERIES_HEADER, prior);
    // The attempt counter copied from the previous topic, before this delivery is counted.
    stamp(record, EventFailurePolicy.ATTEMPT_HEADER, prior);
    return record;
  }

  private static ConsumerRecord<Object, Object> record(String topic, int partition) {
    return new ConsumerRecord<>(topic, partition, 7, "key", "{}");
  }

  private static ListenerExecutionFailedException failure(String group, Exception cause) {
    return new ListenerExecutionFailedException("listener failed", group, cause);
  }

  private static void stamp(ConsumerRecord<Object, Object> record, String name, int value) {
    record.headers().add(name, ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
  }

  private static String text(Headers headers, String name) {
    return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private static int integer(Headers headers, String name) {
    return ByteBuffer.wrap(headers.lastHeader(name).value()).getInt();
  }

  private static Instant instant(Headers headers) {
    return Instant.ofEpochMilli(
        ByteBuffer.wrap(headers.lastHeader(DelayedRetryPolicy.NOT_BEFORE_HEADER).value())
            .getLong());
  }
}
