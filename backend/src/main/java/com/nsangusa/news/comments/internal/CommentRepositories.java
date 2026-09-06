package com.nsangusa.news.comments.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommentRepository extends JpaRepository<Comment, UUID> {
  List<Comment> findByArticleIdAndStateOrderByCreatedAt(UUID articleId, String state);

  List<Comment> findByStateInOrderByCreatedAtAsc(Collection<String> states, Pageable pageable);
}

interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {
  List<ModerationAction> findByCommentIdOrderByCreatedAtDesc(UUID commentId);
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
  List<CommentingPrivilegeRecord> findByUserIdOrderByCreatedAtDesc(UUID userId);
}

interface CommentGlobalSettingsRepository extends JpaRepository<CommentGlobalSettings, Integer> {}

interface ArticleCommentSettingsRepository extends JpaRepository<ArticleCommentSettings, UUID> {}
