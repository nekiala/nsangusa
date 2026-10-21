package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.integration.EventTopics;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

/**
 * Routes a record whose blocking retries are exhausted. Transient failures go to {@code
 * <topic>.retry} once per configured delay, addressed to the one consumer group that failed; other
 * groups sharing the topic skip it. Terminal failures, unscoped failures and exhausted delays go to
 * {@code <topic>.dlt}. Both keep the original partition, so per-aggregate keys stay together.
 */
@Component
class DelayedRetryPolicy {
  static final String GROUP_HEADER = "x-retry-consumer-group";
  static final String ATTEMPT_HEADER = "x-retry-attempt";
  static final String NOT_BEFORE_HEADER = "x-retry-not-before";
  static final String PRIOR_DELIVERIES_HEADER = "x-retry-prior-deliveries";

  // A retry listener waits for its record while holding the poll; stay well inside the
  // default five-minute max.poll.interval.ms.
  static final Duration MAX_DELAY = Duration.ofMinutes(4);
  private static final int MAX_DELAYED_ATTEMPTS = 5;

  private final List<Duration> delays;
  private final Clock clock;

  @Autowired
  DelayedRetryPolicy(@Value("${news.events.retry.delays:30s,120s}") List<Duration> delays) {
    this(delays, Clock.systemUTC());
  }

  DelayedRetryPolicy(List<Duration> delays, Clock clock) {
    if (delays.size() > MAX_DELAYED_ATTEMPTS
        || delays.stream()
            .anyMatch(
                delay ->
                    delay.compareTo(Duration.ofSeconds(1)) < 0 || delay.compareTo(MAX_DELAY) > 0)) {
      throw new IllegalArgumentException(
          "news.events.retry.delays allows at most "
              + MAX_DELAYED_ATTEMPTS
              + " delays, each from 1s to "
              + MAX_DELAY);
    }
    this.delays = List.copyOf(delays);
    this.clock = clock;
  }

  Duration maxDelay() {
    return delays.stream().max(Duration::compareTo).orElse(Duration.ZERO);
  }

  TopicPartition destination(ConsumerRecord<?, ?> record, Exception exception) {
    String base = baseTopic(record.topic());
    String suffix = retries(record, exception) ? EventTopics.RETRY_SUFFIX : EventTopics.DLT_SUFFIX;
    return new TopicPartition(base + suffix, record.partition());
  }

  Headers headers(ConsumerRecord<?, ?> record, Exception exception) {
    var headers = new RecordHeaders();
    headers.add(
        EventFailurePolicy.ATTEMPT_HEADER,
        ByteBuffer.allocate(Integer.BYTES).putInt(deliveries(record)).array());
    if (!retries(record, exception)) {
      return headers;
    }
    int attempt = delayedAttempt(record);
    headers.add(GROUP_HEADER, failedGroup(record, exception).getBytes(StandardCharsets.UTF_8));
    headers.add(ATTEMPT_HEADER, ByteBuffer.allocate(Integer.BYTES).putInt(attempt + 1).array());
    headers.add(
        NOT_BEFORE_HEADER,
        ByteBuffer.allocate(Long.BYTES)
            .putLong(Instant.now(clock).plus(delays.get(attempt)).toEpochMilli())
            .array());
    headers.add(
        PRIOR_DELIVERIES_HEADER,
        ByteBuffer.allocate(Integer.BYTES).putInt(deliveries(record)).array());
    return headers;
  }

  /** Deliveries before this record reached its current topic; zero on a main topic. */
  static int priorDeliveries(ConsumerRecord<?, ?> record) {
    return isRetryTopic(record.topic()) ? intHeader(record, PRIOR_DELIVERIES_HEADER, 0) : 0;
  }

  static boolean isRetryTopic(String topic) {
    return topic.endsWith(EventTopics.RETRY_SUFFIX);
  }

  static String baseTopic(String topic) {
    return isRetryTopic(topic)
        ? topic.substring(0, topic.length() - EventTopics.RETRY_SUFFIX.length())
        : topic;
  }

  static String retryTwinOf(String groupId) {
    return groupId != null && groupId.endsWith(EventTopics.RETRY_GROUP_SUFFIX)
        ? groupId.substring(0, groupId.length() - EventTopics.RETRY_GROUP_SUFFIX.length())
        : null;
  }

  static Instant notBefore(ConsumerRecord<?, ?> record) {
    var header = record.headers().lastHeader(NOT_BEFORE_HEADER);
    return header == null || header.value() == null || header.value().length != Long.BYTES
        ? Instant.EPOCH
        : Instant.ofEpochMilli(ByteBuffer.wrap(header.value()).getLong());
  }

  static String addressedGroup(ConsumerRecord<?, ?> record) {
    var header = record.headers().lastHeader(GROUP_HEADER);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }

  private boolean retries(ConsumerRecord<?, ?> record, Exception exception) {
    return "transient".equals(EventFailurePolicy.category(exception))
        && delayedAttempt(record) < delays.size()
        && failedGroup(record, exception) != null;
  }

  // Header values on a main topic are ignored: only the retry tier counts its own attempts.
  private static int delayedAttempt(ConsumerRecord<?, ?> record) {
    return isRetryTopic(record.topic()) ? Math.max(0, intHeader(record, ATTEMPT_HEADER, 0)) : 0;
  }

  // Deliveries so far, including the one that just failed.
  private static int deliveries(ConsumerRecord<?, ?> record) {
    return Math.max(
        intHeader(record, EventFailurePolicy.ATTEMPT_HEADER, 1), priorDeliveries(record) + 1);
  }

  private static String failedGroup(ConsumerRecord<?, ?> record, Exception exception) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = exception;
        cause != null && visited.add(cause);
        cause = cause.getCause()) {
      if (cause instanceof ListenerExecutionFailedException failed && failed.getGroupId() != null) {
        String twin = retryTwinOf(failed.getGroupId());
        return twin == null ? failed.getGroupId() : twin;
      }
    }
    // Without a known group the retry would reach every group on the topic; dead-letter instead.
    return isRetryTopic(record.topic()) ? addressedGroup(record) : null;
  }

  private static int intHeader(ConsumerRecord<?, ?> record, String name, int fallback) {
    var header = record.headers().lastHeader(name);
    return header == null || header.value() == null || header.value().length != Integer.BYTES
        ? fallback
        : ByteBuffer.wrap(header.value()).getInt();
  }
}
