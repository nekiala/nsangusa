package com.nsangusa.news.publication.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ScheduledPublicationRepository extends JpaRepository<ScheduledPublication, UUID> {
  List<ScheduledPublication> findByStatusAndScheduledForLessThanEqual(
      String status, Instant scheduledFor);
}
