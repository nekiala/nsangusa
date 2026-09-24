package com.nsangusa.news.aieditorial.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AiRequestRepository extends JpaRepository<AiRequestRecord, UUID> {
  List<AiRequestRecord> findTop100ByStoryCandidateIdOrderByCreatedAtDesc(UUID storyCandidateId);

  Optional<AiRequestRecord> findFirstByEventIdAndOperationAndStatusOrderByCreatedAtDesc(
      UUID eventId, String operation, String status);

  Optional<AiRequestRecord> findFirstByEventIdAndOperationOrderByCreatedAtAsc(
      UUID eventId, String operation);

  Optional<AiRequestRecord> findFirstByEventIdAndStoryCandidateIdAndOperationOrderByCreatedAtAsc(
      UUID eventId, UUID storyCandidateId, String operation);

  Optional<AiRequestRecord> findFirstByEventIdAndStoryCandidateIdOrderByCreatedAtAsc(
      UUID eventId, UUID storyCandidateId);
}
