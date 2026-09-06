package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
