package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
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

  boolean existsByEditChainId(String editChainId);

  boolean existsByEditChainIdAndIdNot(String editChainId, UUID id);

  Optional<SourcePost> findByEditChainId(String editChainId);

  List<SourcePost> findByPostIdIn(Collection<String> postIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from SourcePost p where p.id = :id")
  Optional<SourcePost> findLockedById(UUID id);

  List<SourcePost> findByAccountIdAndStatusNot(String accountId, String status);

  @Query(
      value =
          """
          select *
            from source_posts
           where monitored_account_id = :accountId
             and status <> 'deleted'
           order by last_checked_at nulls first, published_at desc
           limit :limit
          """,
      nativeQuery = true)
  List<SourcePost> findReconciliationCandidates(UUID accountId, int limit);

  long countByIdInAndStatus(Collection<UUID> ids, String status);

  List<SourcePost> findAllByOrderByPublishedAtDesc(Pageable pageable);

  List<SourcePost> findByAccountIdOrderByPublishedAtDesc(String accountId, Pageable pageable);

  List<SourcePost> findByStatusOrderByPublishedAtDesc(String status, Pageable pageable);

  List<SourcePost> findByAccountIdAndStatusOrderByPublishedAtDesc(
      String accountId, String status, Pageable pageable);

  @Query(
      value =
          """
        select 'story_candidate' as "associationType", sc.id as "associatedId", sc.status as state
          from story_candidate_sources scs
          join story_candidates sc on sc.id = scs.story_candidate_id
         where scs.source_post_id = :sourcePostId
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

  boolean existsBySourcePostIdAndRelatedPostIdAndRelationshipType(
      UUID sourcePostId, String relatedPostId, String relationshipType);
}
