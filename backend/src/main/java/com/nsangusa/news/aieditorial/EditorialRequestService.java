package com.nsangusa.news.aieditorial;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EditorialRequestService {
  List<AiRequestView> list(UUID storyCandidateId);

  record AiRequestView(
      UUID id,
      UUID storyCandidateId,
      String operation,
      String provider,
      String model,
      String promptVersion,
      String status,
      String errorCode,
      Instant createdAt,
      Instant completedAt,
      long inputTokens,
      long outputTokens,
      Object result,
      EditorialProviders.ProviderConfiguration configuration) {
    public AiRequestView(
        UUID id,
        UUID storyCandidateId,
        String operation,
        String provider,
        String model,
        String promptVersion,
        String status,
        String errorCode,
        Instant createdAt,
        Instant completedAt,
        long inputTokens,
        long outputTokens,
        Object result) {
      this(
          id,
          storyCandidateId,
          operation,
          provider,
          model,
          promptVersion,
          status,
          errorCode,
          createdAt,
          completedAt,
          inputTokens,
          outputTokens,
          result,
          null);
    }
  }
}
