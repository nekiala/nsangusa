package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nsangusa.news.integration.EventTopics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxRelayTests {
  @Test
  void marksOutboxPublishedOnlyAfterKafkaAcknowledgesRecord() throws Exception {
    var repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var event = event();
    when(repository.findUnpublished(any())).thenReturn(List.of(event));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    new OutboxRelay(repository, kafka).relay();

    var record = ArgumentCaptor.forClass(ProducerRecord.class);
    org.mockito.Mockito.verify(kafka).send(record.capture());
    assertThat(record.getValue().topic()).isEqualTo(EventTopics.forType(event.eventType));
    assertThat(record.getValue().key()).isEqualTo(event.aggregateId.toString());
    assertThat(record.getValue().value()).isEqualTo(event.envelopeJson);
    assertThat(record.getValue().headers().lastHeader("eventType").value())
        .isEqualTo(event.eventType.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(event.publishedAt).isNotNull();
  }

  @Test
  void kafkaFailureLeavesOutboxEventUnpublishedForRetry() {
    var repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var event = event();
    when(repository.findUnpublished(any())).thenReturn(List.of(event));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

    assertThatThrownBy(() -> new OutboxRelay(repository, kafka).relay())
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(event.publishedAt).isNull();
  }

  private static OutboxEvent event() {
    UUID aggregateId = UUID.randomUUID();
    return new OutboxEvent(
        UUID.randomUUID(),
        "ArticlePublished",
        aggregateId,
        UUID.randomUUID(),
        null,
        "article-published:" + aggregateId + ":1",
        "{\"eventType\":\"ArticlePublished\"}",
        Instant.now());
  }
}
