package com.nsangusa.news.comments.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CommentRepository extends JpaRepository<Comment, UUID> {
  List<Comment> findByArticleIdAndStateOrderByCreatedAt(UUID articleId, String state);

  List<Comment> findByStateInOrderByCreatedAtAsc(Collection<String> states, Pageable pageable);

  List<Comment> findByArticleIdAndAuthorIdOrderByCreatedAt(UUID articleId, UUID authorId);

  @Query(
      "select c from Comment c where (:state is null or c.state = :state) "
          + "and (:articleId is null or c.articleId = :articleId) order by c.createdAt, c.id")
  Page<Comment> queue(String state, UUID articleId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Comment c where c.id = :id")
  Optional<Comment> findLockedById(UUID id);
}

interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {
  List<ModerationAction> findByCommentIdOrderByCreatedAtDesc(UUID commentId, Pageable pageable);
}

interface CommentReportRepository extends JpaRepository<CommentReport, UUID> {
  boolean existsByCommentIdAndReporterId(UUID commentId, UUID reporterId);

  long countByCommentIdAndStatus(UUID commentId, String status);

  List<CommentReport> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

  List<CommentReport> findByCommentIdAndStatus(UUID commentId, String status);
}

interface CommentingPrivilegeRepository extends JpaRepository<CommentingPrivilege, UUID> {}

interface CommentingPrivilegeRecordRepository
    extends JpaRepository<CommentingPrivilegeRecord, UUID> {
  List<CommentingPrivilegeRecord> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

interface CommentGlobalSettingsRepository extends JpaRepository<CommentGlobalSettings, Integer> {}

interface ArticleCommentSettingsRepository extends JpaRepository<ArticleCommentSettings, UUID> {}
