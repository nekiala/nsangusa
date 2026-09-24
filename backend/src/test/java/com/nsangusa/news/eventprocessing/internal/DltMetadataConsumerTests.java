package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DltMetadataConsumerTests {
  @Mock FailedEventRepository failedEvents;

  @Test
  void persistsPoisonMessageMetadataFromDltHeaders() {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    String payload =
        """
        {"eventId":"%s","eventType":"XPostDiscovered","aggregateId":"%s"}
        """
            .formatted(eventId, aggregateId);
    var record = new ConsumerRecord<String, String>("news.ingestion.v1.dlt", 2, 42, "key", payload);
    record
        .headers()
        .add(
            new RecordHeader(
                "kafka_dlt-exception-fqcn",
                "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8)))
        .add(
            new RecordHeader(
                "kafka_dlt-original-topic", "news.ingestion.v1".getBytes(StandardCharsets.UTF_8)));
    var consumer =
        new DltMetadataConsumer(failedEvents, new ObjectMapper().findAndRegisterModules());

    consumer.consume(record);

    var captor = ArgumentCaptor.forClass(FailedEvent.class);
    verify(failedEvents).save(captor.capture());
    assertThat(captor.getValue().eventId).isEqualTo(eventId);
    assertThat(captor.getValue().aggregateId).isEqualTo(aggregateId);
    assertThat(captor.getValue().poisonMessage).isTrue();
    assertThat(captor.getValue().status).isEqualTo("poison");
  }

  @Test
  void retainsBinaryOriginalCoordinatesAndAttemptsWithoutPersistingExceptionSecrets() {
    var record =
        new ConsumerRecord<String, String>(
            "news.editorial.v1.dlt",
            0,
            90,
            "key",
            "{\"eventId\":\""
                + UUID.randomUUID()
                + "\",\"eventType\":\"ArticleReadyForReview\",\"aggregateId\":\""
                + UUID.randomUUID()
                + "\"}");
    record
        .headers()
        .add("kafka_dlt-original-topic", "news.editorial.v1".getBytes(StandardCharsets.UTF_8))
        .add("kafka_dlt-original-partition", ByteBuffer.allocate(4).putInt(2).array())
        .add("kafka_dlt-original-offset", ByteBuffer.allocate(8).putLong(45).array())
        .add(
            "kafka_dlt-original-consumer-group",
            "article-review-v1".getBytes(StandardCharsets.UTF_8))
        .add(EventFailurePolicy.ATTEMPT_HEADER, ByteBuffer.allocate(4).putInt(4).array())
        .add(EventFailurePolicy.CATEGORY_HEADER, "authorization".getBytes(StandardCharsets.UTF_8))
        .add(
            "kafka_dlt-exception-fqcn",
            "ListenerExecutionFailedException".getBytes(StandardCharsets.UTF_8))
        .add(
            "kafka_dlt-exception-cause-fqcn",
            "AccessDeniedException".getBytes(StandardCharsets.UTF_8))
        .add("kafka_dlt-exception-message", "password=secret".getBytes(StandardCharsets.UTF_8));

    new DltMetadataConsumer(failedEvents, new ObjectMapper()).consume(record);

    var captor = ArgumentCaptor.forClass(FailedEvent.class);
    verify(failedEvents).save(captor.capture());
    var failure = captor.getValue();
    assertThat(failure.originalTopic).isEqualTo("news.editorial.v1");
    assertThat(failure.originalPartition).isEqualTo(2);
    assertThat(failure.originalOffset).isEqualTo(45);
    assertThat(failure.consumerGroup).isEqualTo("article-review-v1");
    assertThat(failure.deliveryAttempt).isEqualTo(4);
    assertThat(failure.exceptionClass).isEqualTo("AccessDeniedException");
    assertThat(failure.exceptionMessage).isEqualTo("Event authorization rejected");
    assertThat(failure.poisonMessage).isTrue();
  }

  @Test
  void oversizedInvalidEventTypesCannotPoisonTheMetadataDatabaseWriter() {
    var record =
        new ConsumerRecord<String, String>(
            "news.editorial.v1.dlt",
            0,
            90,
            "key",
            "{\"eventId\":\""
                + UUID.randomUUID()
                + "\",\"eventType\":\""
                + "x".repeat(101)
                + "\"}");
    new DltMetadataConsumer(failedEvents, new ObjectMapper()).consume(record);
    var captor = ArgumentCaptor.forClass(FailedEvent.class);
    verify(failedEvents).save(captor.capture());
    assertThat(captor.getValue().eventType).isNull();
    assertThat(captor.getValue().poisonMessage).isTrue();
  }
}
