package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaEventStoreTests {
  @Mock OutboxRepository outbox;
  @Mock ProcessedEventRepository processed;

  @Test
  void persistsCompleteOutboxEnvelopeAndConsumerMarker() {
    var store =
        new JpaEventStore(
            outbox,
            processed,
            new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator());
    UUID aggregateId = UUID.randomUUID();

    UUID eventId =
        store.enqueue(
            "ArticleApproved",
            aggregateId,
            aggregateId,
            null,
            "approval:" + aggregateId,
            new ArticleApproved(aggregateId, UUID.randomUUID()));
    when(processed.existsByEventIdAndConsumerName(eventId, "consumer")).thenReturn(false);

    assertThat(store.wasProcessed(eventId, "consumer")).isFalse();
    store.markProcessed(eventId, "consumer");
    verify(outbox).save(any(OutboxEvent.class));
    verify(processed).save(any(ProcessedEvent.class));
  }
}
