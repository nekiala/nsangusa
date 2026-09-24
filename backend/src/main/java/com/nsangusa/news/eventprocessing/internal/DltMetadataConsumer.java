package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DltMetadataConsumer {
  private final FailedEventRepository failedEvents;
  private final ObjectMapper objectMapper;

  DltMetadataConsumer(FailedEventRepository failedEvents, ObjectMapper objectMapper) {
    this.failedEvents = failedEvents;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topicPattern = "news\\..*(\\.dlt|-dlt)$", groupId = "failure-metadata-v1")
  @Transactional
  void consume(ConsumerRecord<String, String> record) {
    if (failedEvents
        .findByDltTopicAndDltPartitionAndDltOffset(
            record.topic(), record.partition(), record.offset())
        .isPresent()) {
      return;
    }
    String payload = record.value() == null ? "" : record.value();
    var parsed = parseEnvelope(payload);
    String exceptionClass =
        bounded(
            textHeader(
                record,
                "kafka_dlt-exception-cause-fqcn",
                "kafka_dlt-exception-fqcn",
                "kafka_exception-fqcn",
                "kafka_dlt-key-exception-fqcn"),
            500);
    String category = textHeader(record, EventFailurePolicy.CATEGORY_HEADER);
    String originalTopic =
        defaultString(
            bounded(
                textHeader(
                    record,
                    "kafka_dlt-original-topic",
                    "kafka_original-topic",
                    "kafka_originalTopic"),
                249),
            stripDltSuffix(record.topic()));
    int originalPartition =
        intHeader(
            record,
            record.partition(),
            "kafka_dlt-original-partition",
            "kafka_original-partition",
            "kafka_originalPartitionId");
    long originalOffset =
        longHeader(
            record,
            record.offset(),
            "kafka_dlt-original-offset",
            "kafka_original-offset",
            "kafka_originalOffset");
    int deliveryAttempt =
        Math.max(
            1,
            intHeader(
                record,
                1,
                EventFailurePolicy.ATTEMPT_HEADER,
                "kafka_deliveryAttempt",
                "retry_topic-attempts",
                "delivery-attempt"));
    boolean poison =
        parsed.eventId == null
            || parsed.eventType == null
            || java.util.Set.of("invalid", "authorization", "invariant")
                .contains(category == null ? "" : category)
            || (exceptionClass != null
                && (exceptionClass.contains("IllegalArgumentException")
                    || exceptionClass.contains("ConstraintViolationException")
                    || exceptionClass.contains("JsonProcessingException")
                    || exceptionClass.contains("DeserializationException")
                    || exceptionClass.contains("AccessDeniedException")
                    || exceptionClass.contains("AuthenticationException")
                    || exceptionClass.contains("TerminalEventException")));
    failedEvents.save(
        new FailedEvent(
            parsed.eventId,
            parsed.eventType,
            parsed.aggregateId,
            record.topic(),
            record.partition(),
            record.offset(),
            originalTopic,
            originalPartition,
            originalOffset,
            bounded(textHeader(record, "kafka_dlt-original-consumer-group", "kafka_groupId"), 255),
            payload,
            exceptionClass,
            EventFailurePolicy.safeMessage(category == null && poison ? "invalid" : category),
            deliveryAttempt,
            poison));
  }

  private ParsedEnvelope parseEnvelope(String payload) {
    try {
      var root = objectMapper.readTree(payload);
      return new ParsedEnvelope(
          uuid(root.path("eventId").asText(null)),
          bounded(root.path("eventType").asText(null), 100),
          uuid(root.path("aggregateId").asText(null)));
    } catch (Exception ignored) {
      return new ParsedEnvelope(null, null, null);
    }
  }

  private static String bounded(String value, int maximum) {
    return value == null || value.length() > maximum || value.indexOf('\0') >= 0 ? null : value;
  }

  private static String textHeader(ConsumerRecord<?, ?> record, String... names) {
    for (String name : names) {
      Header header = record.headers().lastHeader(name);
      if (header != null && header.value() != null) {
        return new String(header.value(), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static int intHeader(ConsumerRecord<?, ?> record, int fallback, String... names) {
    byte[] value = bytesHeader(record, names);
    if (value == null) {
      return fallback;
    }
    if (value.length == Integer.BYTES) {
      return ByteBuffer.wrap(value).getInt();
    }
    try {
      return Integer.parseInt(new String(value, StandardCharsets.UTF_8));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long longHeader(ConsumerRecord<?, ?> record, long fallback, String... names) {
    byte[] value = bytesHeader(record, names);
    if (value == null) {
      return fallback;
    }
    if (value.length == Long.BYTES) {
      return ByteBuffer.wrap(value).getLong();
    }
    try {
      return Long.parseLong(new String(value, StandardCharsets.UTF_8));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static byte[] bytesHeader(ConsumerRecord<?, ?> record, String... names) {
    for (String name : names) {
      Header header = record.headers().lastHeader(name);
      if (header != null) {
        return header.value();
      }
    }
    return null;
  }

  private static String stripDltSuffix(String topic) {
    return topic.replaceFirst("(\\.dlt|-dlt)$", "");
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static UUID uuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private record ParsedEnvelope(UUID eventId, String eventType, UUID aggregateId) {}
}
