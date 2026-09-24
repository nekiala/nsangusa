package com.nsangusa.news.storyprocessing.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StoryCandidateRepository extends JpaRepository<StoryCandidate, UUID> {
  @Query(
      """
      select candidate
        from StoryCandidate candidate
       where candidate.topic = :topic
         and candidate.status = 'collecting'
         and candidate.lastSourceAt >= :cutoff
       order by candidate.lastSourceAt desc
      """)
  List<StoryCandidate> findCollecting(String topic, Instant cutoff, Pageable pageable);

  @Query(
      """
      select candidate
        from StoryCandidate candidate
       where candidate.conversationId = :conversationId
         and candidate.status = 'collecting'
         and candidate.lastSourceAt >= :cutoff
       order by candidate.lastSourceAt desc
      """)
  List<StoryCandidate> findCollectingByConversationId(
      String conversationId, Instant cutoff, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select candidate
        from StoryCandidate candidate
       where candidate.id in :ids
       order by candidate.id
      """)
  List<StoryCandidate> findLockedByIdIn(Collection<UUID> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select candidate
        from StoryCandidate candidate
       where candidate.status = :status
         and candidate.lastSourceAt <= :cutoff
       order by candidate.id
      """)
  List<StoryCandidate> findByStatusAndLastSourceAtLessThanEqualOrderByLastSourceAtAsc(
      String status, Instant cutoff, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select candidate from StoryCandidate candidate where candidate.id = :id")
  Optional<StoryCandidate> findLockedById(UUID id);
}

interface StoryCandidateSourceRepository extends JpaRepository<StoryCandidateSource, UUID> {
  boolean existsBySourcePostId(UUID sourcePostId);

  List<StoryCandidateSource> findByStoryCandidateIdOrderByPublishedAtAsc(UUID storyCandidateId);
}
