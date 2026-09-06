package com.nsangusa.news.eventprocessing;

import java.util.UUID;

public interface DurableEventPublisher {
  UUID enqueue(
      String eventType,
      UUID aggregateId,
      UUID correlationId,
      UUID causationId,
      String idempotencyKey,
      Object payload);
}
