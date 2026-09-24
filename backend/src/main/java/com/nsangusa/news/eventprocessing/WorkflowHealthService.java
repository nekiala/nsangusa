package com.nsangusa.news.eventprocessing;

import java.time.Instant;
import java.util.Map;

public interface WorkflowHealthService {
  WorkflowHealth snapshot();

  record WorkflowHealth(
      Instant checkedAt,
      long pendingOutbox,
      Instant oldestPendingOutboxAt,
      Map<String, Long> failedEvents,
      long pendingReplays) {}
}
