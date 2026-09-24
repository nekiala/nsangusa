package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

@Component
class ReplayScopeInterceptor implements RecordInterceptor<Object, Object> {
  private final ReplaySafetyRegistry safety;
  private final ObjectMapper mapper;

  ReplayScopeInterceptor(ReplaySafetyRegistry safety, ObjectMapper mapper) {
    this.safety = safety;
    this.mapper = mapper;
  }

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    if (record.topic().endsWith(".dlt") || record.topic().endsWith("-dlt")) {
      return record;
    }
    var target = record.headers().lastHeader(EventFailurePolicy.REPLAY_GROUP_HEADER);
    if (target == null) {
      return record;
    }
    if (target.value() == null
        || !consumer
            .groupMetadata()
            .groupId()
            .equals(new String(target.value(), StandardCharsets.UTF_8))) {
      return null;
    }
    UUID aggregateId = aggregateId(record.value());
    return safety.isSuppressed(aggregateId) ? null : record;
  }

  private UUID aggregateId(Object value) {
    try {
      return UUID.fromString(mapper.readTree((String) value).path("aggregateId").asText());
    } catch (Exception ignored) {
      // Invalid replay payloads still reach the listener's contract validation and DLT handling.
      return null;
    }
  }
}
