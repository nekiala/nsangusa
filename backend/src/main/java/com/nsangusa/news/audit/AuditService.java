package com.nsangusa.news.audit;

import java.util.Map;
import java.util.UUID;

public interface AuditService {
  void record(
      UUID actorId, String action, String targetType, UUID targetId, Map<String, String> metadata);
}
