package com.nsangusa.news.comments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommentService {
  UUID submit(UUID articleId, UUID authorId, String body, UUID parentId);

  void edit(UUID commentId, UUID authorId, String body);

  void deleteByAuthor(UUID commentId, UUID authorId);

  UUID report(UUID commentId, UUID reporterId, String reason, String details);

  void moderate(UUID commentId, String decision, String reason, UUID moderatorId);

  List<CommentView> approvedForArticle(UUID articleId);

  List<ModerationQueueItem> moderationQueue(int limit);

  List<ReportView> reportQueue(int limit);

  List<ModerationHistoryView> moderationHistory(UUID commentId);

  void suspend(UUID userId, Instant until, String reason, UUID moderatorId);

  void restorePrivilege(UUID userId, String reason, UUID moderatorId);

  PrivilegeView privilege(UUID userId);

  List<PrivilegeHistoryView> privilegeHistory(UUID userId);

  GlobalSettingsView globalSettings();

  void updateGlobalSettings(GlobalSettingsCommand command, UUID moderatorId);

  ArticleSettingsView articleSettings(UUID articleId);

  void updateArticleSettings(UUID articleId, ArticleSettingsCommand command, UUID moderatorId);

  record CommentView(
      UUID id,
      UUID authorId,
      String body,
      UUID parentId,
      String state,
      Instant createdAt,
      Instant editedAt,
      boolean deletedByAuthor) {}

  record ModerationQueueItem(
      UUID id,
      UUID articleId,
      UUID authorId,
      String body,
      String state,
      double spamScore,
      String spamReason,
      long openReports,
      Instant createdAt) {}

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
      Instant updatedAt) {}

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
      Instant updatedAt) {}

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
      Instant updatedAt) {}

  record ArticleSettingsCommand(Boolean enabledOverride, Boolean requireApprovalOverride) {}
}
