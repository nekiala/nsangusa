package com.nsangusa.news.eventprocessing.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select e from OutboxEvent e
       where e.publishedAt is null
         and not exists (
           select s.aggregateId from ReplaySuppression s where s.aggregateId = e.aggregateId
         )
       order by e.createdAt
      """)
  List<OutboxEvent> findUnpublished(Pageable pageable);
}
