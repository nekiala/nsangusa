package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.integration.EventTopics;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxRelay {
  private final OutboxRepository repository;
  private final KafkaTemplate<String, String> kafkaTemplate;

  OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
  }

  @Scheduled(fixedDelayString = "${news.outbox.poll-interval:500}")
  @Transactional
  void relay() throws Exception {
    for (var event : repository.findUnpublished(PageRequest.of(0, 100))) {
      var record =
          new ProducerRecord<>(
              EventTopics.forType(event.eventType),
              event.aggregateId.toString(),
              event.envelopeJson);
      record
          .headers()
          .add("eventType", event.eventType.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
      event.published(Instant.now());
    }
  }
}
