package com.nsangusa.news.publication.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ScheduledPublicationRepository extends JpaRepository<ScheduledPublication, UUID> {
  @Query(
      """
      select s from ScheduledPublication s
      where (:status is null or s.status = :status)
        and (:articleId is null or s.articleId = :articleId)
      """)
  Page<ScheduledPublication> inventory(
      @Param("status") String status, @Param("articleId") UUID articleId, Pageable pageable);

  boolean existsByArticleIdAndStatusIn(UUID articleId, List<String> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from ScheduledPublication s where s.id = :id")
  Optional<ScheduledPublication> lockById(@Param("id") UUID id);

  @Query(
      """
      select s.id from ScheduledPublication s
      where s.status = 'scheduled' and s.scheduledFor <= :cutoff
      order by s.scheduledFor, s.id
      """)
  List<UUID> dueIds(@Param("cutoff") Instant cutoff, Pageable pageable);

  @Query(
      value =
          """
          select * from scheduled_publications
          where id = :id and status = 'scheduled' and scheduled_for <= :cutoff
          for update skip locked
          """,
      nativeQuery = true)
  Optional<ScheduledPublication> claimDue(@Param("id") UUID id, @Param("cutoff") Instant cutoff);
}
