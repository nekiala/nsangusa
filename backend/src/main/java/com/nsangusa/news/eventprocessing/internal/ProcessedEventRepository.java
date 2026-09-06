package com.nsangusa.news.eventprocessing.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
  boolean existsByEventIdAndConsumerName(UUID eventId, String consumerName);
}
