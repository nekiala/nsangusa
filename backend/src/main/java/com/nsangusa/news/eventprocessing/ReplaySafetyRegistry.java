package com.nsangusa.news.eventprocessing;

import java.util.UUID;

public interface ReplaySafetyRegistry {
  void suppressAggregate(UUID aggregateId, String reason, UUID actorId);

  boolean isSuppressed(UUID aggregateId);
}
