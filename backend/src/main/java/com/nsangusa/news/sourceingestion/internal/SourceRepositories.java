package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface MonitoredXAccountRepository extends JpaRepository<MonitoredXAccount, UUID> {
  Optional<MonitoredXAccount> findByHandleIgnoreCase(String handle);

  Optional<MonitoredXAccount> findByAccountId(String accountId);

  List<MonitoredXAccount> findByMonitoringEnabledTrueAndRemovedAtIsNull();

  List<MonitoredXAccount> findByRemovedAtIsNullOrderByHandleAsc();

  List<MonitoredXAccount> findAllByOrderByHandleAsc();
}

interface SourcePostRepository extends JpaRepository<SourcePost, UUID> {
  boolean existsByPostId(String postId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from SourcePost p where p.id = :id")
  Optional<SourcePost> findLockedById(UUID id);

  List<SourcePost> findByAccountIdAndStatusNot(String accountId, String status);

  List<SourcePost> findAllByOrderByPublishedAtDesc(Pageable pageable);

  List<SourcePost> findByAccountIdOrderByPublishedAtDesc(String accountId, Pageable pageable);

  List<SourcePost> findByStatusOrderByPublishedAtDesc(String status, Pageable pageable);

  List<SourcePost> findByAccountIdAndStatusOrderByPublishedAtDesc(
      String accountId, String status, Pageable pageable);

  @Query(
      value =
          """
        select 'story_candidate' as "associationType", sc.id as "associatedId", sc.status as state
          from story_candidates sc where sc.primary_source_post_id = :sourcePostId
        union all
        select 'article' as "associationType", a.id as "associatedId", a.state as state
          from article_sources ars join articles a on a.id = ars.article_id
         where ars.source_post_id = :sourcePostId
        order by "associationType", "associatedId"
        """,
      nativeQuery = true)
  List<SourceAssociationProjection> findAssociations(UUID sourcePostId);
}

interface SourceAssociationProjection {
  String getAssociationType();

  UUID getAssociatedId();

  String getState();
}

interface BlockedSourceAccountRepository extends JpaRepository<BlockedSourceAccount, String> {
  List<BlockedSourceAccount> findAllByOrderByCreatedAtDesc();
}

interface SourceTombstoneRepository extends JpaRepository<SourceTombstone, UUID> {
  boolean existsByPostId(String postId);
}

interface SourceComplianceActionRepository extends JpaRepository<SourceComplianceAction, UUID> {}

interface SourceRelationshipRepository extends JpaRepository<SourceRelationshipEntity, UUID> {
  List<SourceRelationshipEntity> findBySourcePostIdOrderByRelationshipTypeAsc(UUID sourcePostId);
}
