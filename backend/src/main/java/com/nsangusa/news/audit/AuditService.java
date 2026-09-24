package com.nsangusa.news.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditService {
  void record(
      UUID actorId, String action, String targetType, UUID targetId, Map<String, String> metadata);

  AuditPage list(
      String action,
      String targetType,
      UUID actorId,
      UUID targetId,
      Instant from,
      Instant to,
      int page,
      int size);

  record AuditPage(List<AuditView> items, int page, int size, long total) {}

  record AuditView(
      UUID id,
      UUID actorId,
      String action,
      String targetType,
      UUID targetId,
      Instant occurredAt,
      Map<String, Object> metadata) {}
}
