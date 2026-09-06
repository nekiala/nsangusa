package com.nsangusa.news.eventprocessing;

import java.util.UUID;

public interface ProcessedEventRegistry {
  boolean wasProcessed(UUID eventId, String consumer);

  void markProcessed(UUID eventId, String consumer);
}
