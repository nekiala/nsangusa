package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Scopes redelivered records to the one consumer group that failed them. Replays and delayed
 * retries are skipped by every other group, and by the target group once the aggregate is
 * compliance-suppressed. A retry record is held until its not-before time.
 */
@Component
class DeliveryScopeInterceptor
    implements RecordInterceptor<Object, Object>, ApplicationListener<ContextClosedEvent> {
  private static final Duration WAIT_SLICE = Duration.ofMillis(250);

  private final ReplaySafetyRegistry safety;
  private final ObjectMapper mapper;
  private final Duration maxDelay;
  private final Clock clock;
  private final Sleeper sleeper;
  private volatile boolean closing;

  @Autowired
  DeliveryScopeInterceptor(
      ReplaySafetyRegistry safety, ObjectMapper mapper, DelayedRetryPolicy retries) {
    this(safety, mapper, retries.maxDelay(), Clock.systemUTC(), Thread::sleep);
  }

  DeliveryScopeInterceptor(
      ReplaySafetyRegistry safety,
      ObjectMapper mapper,
      Duration maxDelay,
      Clock clock,
      Sleeper sleeper) {
    this.safety = safety;
    this.mapper = mapper;
    this.maxDelay = maxDelay;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    if (record.topic().endsWith(".dlt") || record.topic().endsWith("-dlt")) {
      return record;
    }
    String group = consumer.groupMetadata().groupId();
    if (DelayedRetryPolicy.isRetryTopic(record.topic())) {
      String twin = DelayedRetryPolicy.retryTwinOf(group);
      if (twin == null || !twin.equals(DelayedRetryPolicy.addressedGroup(record))) {
        return null;
      }
      awaitNotBefore(DelayedRetryPolicy.notBefore(record));
      return safety.isSuppressed(aggregateId(record.value())) ? null : record;
    }
    var target = record.headers().lastHeader(EventFailurePolicy.REPLAY_GROUP_HEADER);
    if (target == null) {
      return record;
    }
    if (target.value() == null
        || !group.equals(new String(target.value(), StandardCharsets.UTF_8))) {
      return null;
    }
    return safety.isSuppressed(aggregateId(record.value())) ? null : record;
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    // Shutdown processes a waiting retry early rather than delaying container stop.
    closing = true;
  }

  private void awaitNotBefore(Instant notBefore) {
    // A forged or skewed far-future header waits no longer than the longest configured delay.
    Instant deadline = Instant.now(clock).plus(maxDelay);
    Instant until = notBefore.isAfter(deadline) ? deadline : notBefore;
    while (!closing) {
      Duration remaining = Duration.between(Instant.now(clock), until);
      if (remaining.isNegative() || remaining.isZero()) {
        return;
      }
      try {
        sleeper.sleep(Math.min(remaining.toMillis(), WAIT_SLICE.toMillis()));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private UUID aggregateId(Object value) {
    try {
      return UUID.fromString(mapper.readTree((String) value).path("aggregateId").asText());
    } catch (Exception ignored) {
      // Invalid payloads still reach the listener's contract validation and DLT handling.
      return null;
    }
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long milliseconds) throws InterruptedException;
  }
}
