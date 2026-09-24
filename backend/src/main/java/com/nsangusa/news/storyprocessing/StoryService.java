package com.nsangusa.news.storyprocessing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StoryService {
  StoryPage list(int page, int size);

  UUID regenerate(UUID candidateId, UUID actorId);

  record StoryView(
      UUID id,
      String topic,
      String status,
      Instant createdAt,
      List<UUID> sourceIds,
      UUID articleId) {}

  record StoryPage(List<StoryView> items, int page, int size, long total) {}
}
