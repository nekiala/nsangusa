package com.nsangusa.news.comments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommentService {
  UUID submit(UUID articleId, UUID authorId, String body, UUID parentId);

  void edit(UUID commentId, UUID authorId, String body, long expectedVersion);

  void deleteByAuthor(UUID commentId, UUID authorId, long expectedVersion);

  UUID report(UUID commentId, UUID reporterId, String reason, String details);

  void moderate(
      UUID commentId, String decision, String reason, UUID moderatorId, long expectedVersion);

  DiscussionView discussion(UUID articleId, UUID viewerId, boolean eligible);

  ModerationPage moderationPage(String state, UUID articleId, int page, int size);

  ModerationQueueItem moderationDetail(UUID commentId);

  List<CommentView> approvedForArticle(UUID articleId);

  List<ModerationQueueItem> moderationQueue(int limit);

  List<ReportView> reportQueue(int limit);

  List<ModerationHistoryView> moderationHistory(UUID commentId);

  void suspend(UUID userId, Instant until, String reason, UUID moderatorId, long expectedVersion);

  void restorePrivilege(UUID userId, String reason, UUID moderatorId, long expectedVersion);

  PrivilegeView privilege(UUID userId);

  List<PrivilegeHistoryView> privilegeHistory(UUID userId);

  GlobalSettingsView globalSettings();

  void updateGlobalSettings(GlobalSettingsCommand command, UUID moderatorId, long expectedVersion);

  ArticleSettingsView articleSettings(UUID articleId);

  void updateArticleSettings(
      UUID articleId, ArticleSettingsCommand command, UUID moderatorId, long expectedVersion);

  record DiscussionView(
      List<CommentView> comments,
      UUID viewerId,
      boolean authenticated,
      boolean eligible,
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      int maximumReplyDepth,
      String privilegeStatus,
      Instant suspendedUntil,
      boolean canComment) {}

  record ModerationPage(List<ModerationQueueItem> items, int page, int size, long total) {}

  record CommentView(
      UUID id,
      UUID authorId,
      String authorName,
      String body,
      UUID parentId,
      String state,
      Instant createdAt,
      Instant editedAt,
      boolean deletedByAuthor,
      boolean deleted,
      long version) {}

  record ModerationQueueItem(
      UUID id,
      UUID articleId,
      UUID authorId,
      String authorName,
      String body,
      String state,
      double spamScore,
      String spamReason,
      long openReports,
      Instant createdAt,
      Instant editedAt,
      boolean deletedByAuthor,
      UUID parentId,
      boolean deleted,
      long version,
      String articleHeadline,
      String articleSlug) {}

  record ReportView(
      UUID id,
      UUID commentId,
      UUID reporterId,
      String reason,
      String details,
      String status,
      Instant createdAt) {}

  record ModerationHistoryView(
      UUID id,
      UUID moderatorId,
      String previousState,
      String action,
      String reason,
      Instant createdAt) {}

  record PrivilegeView(
      UUID userId,
      String status,
      Instant suspendedUntil,
      String reason,
      UUID moderatorId,
      Instant updatedAt,
      long version) {}

  record PrivilegeHistoryView(
      UUID id,
      String action,
      Instant suspendedUntil,
      String reason,
      UUID moderatorId,
      Instant createdAt) {}

  record GlobalSettingsView(
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold,
      int reportEscalationThreshold,
      Instant updatedAt,
      long version) {}

  record GlobalSettingsCommand(
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold,
      int reportEscalationThreshold) {}

  record ArticleSettingsView(
      UUID articleId,
      Boolean enabledOverride,
      Boolean requireApprovalOverride,
      Instant updatedAt,
      long version) {}

  record ArticleSettingsCommand(Boolean enabledOverride, Boolean requireApprovalOverride) {}
}
