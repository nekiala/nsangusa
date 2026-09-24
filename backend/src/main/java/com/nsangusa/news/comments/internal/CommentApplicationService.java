package com.nsangusa.news.comments.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.comments.SpamDecisionSupport;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.identity.IdentityService;
import com.nsangusa.news.integration.NewsEvents.CommentSubmitted;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CommentApplicationService implements CommentService {
  private static final Set<String> DECISIONS = Set.of("approved", "rejected", "spam", "deleted");
  private static final Set<String> REPORT_REASONS =
      Set.of("abuse", "harassment", "hate", "misinformation", "spam", "other");

  private final ArticleService articles;
  private final IdentityService identity;
  private final CommentRepository comments;
  private final ModerationActionRepository moderation;
  private final CommentReportRepository reports;
  private final CommentingPrivilegeRepository privileges;
  private final CommentingPrivilegeRecordRepository privilegeRecords;
  private final CommentGlobalSettingsRepository settings;
  private final ArticleCommentSettingsRepository articleSettings;
  private final SpamDecisionSupport spam;
  private final DurableEventPublisher events;
  private final AuditService audit;
  private final CommentGlobalSettings defaults;

  CommentApplicationService(
      ArticleService articles,
      IdentityService identity,
      CommentRepository comments,
      ModerationActionRepository moderation,
      CommentReportRepository reports,
      CommentingPrivilegeRepository privileges,
      CommentingPrivilegeRecordRepository privilegeRecords,
      CommentGlobalSettingsRepository settings,
      ArticleCommentSettingsRepository articleSettings,
      SpamDecisionSupport spam,
      DurableEventPublisher events,
      AuditService audit,
      @Value("${news.comments.enabled:true}") boolean enabled,
      @Value("${news.comments.require-approval:true}") boolean requireApproval,
      @Value("${news.comments.editing-window:15}") int editingWindowMinutes,
      @Value("${news.comments.spam-review-threshold:0.55}") double reviewSpamThreshold,
      @Value("${news.comments.spam-reject-threshold:0.90}") double rejectSpamThreshold,
      @Value("${news.comments.report-escalation-threshold:3}") int reportEscalationThreshold) {
    this.articles = articles;
    this.identity = identity;
    this.comments = comments;
    this.moderation = moderation;
    this.reports = reports;
    this.privileges = privileges;
    this.privilegeRecords = privilegeRecords;
    this.settings = settings;
    this.articleSettings = articleSettings;
    this.spam = spam;
    this.events = events;
    this.audit = audit;
    this.defaults =
        new CommentGlobalSettings(
            enabled,
            requireApproval,
            editingWindowMinutes,
            reviewSpamThreshold,
            rejectSpamThreshold,
            reportEscalationThreshold);
  }

  @Override
  @Transactional
  public UUID submit(UUID articleId, UUID authorId, String body, UUID parentId) {
    var policy = policy(articleId);
    ensureCanComment(authorId, policy);
    String safeBody = safeBody(body);
    validateParent(articleId, parentId);
    var assessment = spam.assess(safeBody);
    String initialState = stateFor(assessment.score(), policy);
    var comment =
        comments.save(
            new Comment(
                articleId,
                authorId,
                safeBody,
                parentId,
                initialState,
                assessment.score(),
                assessment.reason()));
    events.enqueue(
        "CommentSubmitted",
        comment.id,
        comment.id,
        null,
        "comment-submitted:" + comment.id,
        new CommentSubmitted(comment.id, articleId, authorId));
    return comment.id;
  }

  @Override
  @Transactional
  public void edit(UUID commentId, UUID authorId, String body, long expectedVersion) {
    var comment = locked(commentId);
    if (!comment.authorId.equals(authorId)) {
      throw new AccessDeniedException("Only the author can edit this comment");
    }
    version(comment.version, expectedVersion);
    if (comment.deletedAt != null || "deleted".equals(comment.state)) {
      throw new IllegalStateException("Deleted comments cannot be edited");
    }
    var policy = policy(comment.articleId);
    ensureCanComment(authorId, policy);
    if (!Instant.now()
        .isBefore(comment.createdAt.plus(policy.editingWindowMinutes, ChronoUnit.MINUTES))) {
      throw new IllegalStateException("Comment editing window has expired");
    }
    String safeBody = safeBody(body);
    var assessment = spam.assess(safeBody);
    comment.body = safeBody;
    comment.spamScore = assessment.score();
    comment.spamReason = assessment.reason();
    comment.state = stateFor(assessment.score(), policy);
    comment.editedAt = Instant.now();
    comment.updatedAt = comment.editedAt;
    audit.record(authorId, "COMMENT_EDITED", "comment", commentId, Map.of());
  }

  @Override
  @Transactional
  public void deleteByAuthor(UUID commentId, UUID authorId, long expectedVersion) {
    var comment = locked(commentId);
    if (!comment.authorId.equals(authorId)) {
      throw new AccessDeniedException("Only the author can delete this comment");
    }
    version(comment.version, expectedVersion);
    if (comment.deletedAt == null) {
      comment.body = "[deleted]";
      comment.deletedAt = Instant.now();
      comment.updatedAt = comment.deletedAt;
      comment.deletedByAuthor = true;
      comment.spamReason = null;
      comment.spamScore = 0;
      if (!"approved".equals(comment.state)) {
        comment.state = "deleted";
      }
      audit.record(authorId, "COMMENT_DELETED_BY_AUTHOR", "comment", commentId, Map.of());
    }
  }

  @Override
  @Transactional
  public UUID report(UUID commentId, UUID reporterId, String reason, String details) {
    String normalizedReason = required(reason, "Report reason").toLowerCase(Locale.ROOT);
    if (!REPORT_REASONS.contains(normalizedReason)) {
      throw new IllegalArgumentException("Unsupported report reason");
    }
    var comment = locked(commentId);
    if (!policy(comment.articleId).enabled || !visibleParent(comment)) {
      throw new IllegalStateException("Only visible comments can be reported");
    }
    if (comment.authorId.equals(reporterId)) {
      throw new IllegalArgumentException("Authors cannot report their own comments");
    }
    if (comment.deletedAt != null || !"approved".equals(comment.state)) {
      throw new IllegalStateException("Only visible comments can be reported");
    }
    if (reports.existsByCommentIdAndReporterId(commentId, reporterId)) {
      throw new IllegalStateException("Comment has already been reported by this user");
    }
    String safeDetails = optional(details, 2_000);
    var report =
        reports.save(new CommentReport(commentId, reporterId, normalizedReason, safeDetails));
    long openReports = reports.countByCommentIdAndStatus(commentId, "open");
    if (openReports >= currentSettings().reportEscalationThreshold
        && "approved".equals(comment.state)) {
      comment.state = "pending";
      comment.updatedAt = Instant.now();
    }
    audit.record(
        reporterId, "COMMENT_REPORTED", "comment", commentId, Map.of("reason", normalizedReason));
    return report.id;
  }

  @Override
  @Transactional
  public void moderate(
      UUID commentId, String decision, String reason, UUID moderatorId, long expectedVersion) {
    String normalizedDecision = required(decision, "Moderation decision").toLowerCase(Locale.ROOT);
    if (!DECISIONS.contains(normalizedDecision)) {
      throw new IllegalArgumentException("Unsupported moderation decision");
    }
    String safeReason = requiredReason(reason);
    var comment = locked(commentId);
    version(comment.version, expectedVersion);
    if (comment.deletedAt != null || "deleted".equals(comment.state)) {
      throw new IllegalStateException("Deleted comments cannot be moderated again");
    }
    String previousState = comment.state;
    comment.state = normalizedDecision;
    comment.updatedAt = Instant.now();
    if ("deleted".equals(normalizedDecision)) {
      comment.deletedAt = comment.updatedAt;
      comment.deletedByAuthor = false;
      comment.body = "[deleted by moderator]";
    }
    moderation.save(
        new ModerationAction(
            commentId, moderatorId, previousState, normalizedDecision, safeReason));
    reports
        .findByCommentIdAndStatus(commentId, "open")
        .forEach(report -> report.resolve(moderatorId));
    audit.record(
        moderatorId,
        "COMMENT_" + normalizedDecision.toUpperCase(Locale.ROOT),
        "comment",
        commentId,
        safeReason == null ? Map.of() : Map.of("reason", safeReason));
  }

  @Override
  @Transactional(readOnly = true)
  public List<CommentView> approvedForArticle(UUID articleId) {
    if (!policy(articleId).enabled) {
      return List.of();
    }
    return views(visibleComments(articleId));
  }

  @Override
  @Transactional(readOnly = true)
  public DiscussionView discussion(UUID articleId, UUID viewerId, boolean eligible) {
    var policy = policy(articleId);
    var privilege = viewerId == null ? null : privilege(viewerId);
    boolean suspended = privilege != null && "suspended".equals(privilege.status());
    var visible = new LinkedHashMap<UUID, Comment>();
    if (policy.enabled) {
      visibleComments(articleId).forEach(comment -> visible.put(comment.id, comment));
      if (viewerId != null) {
        comments
            .findByArticleIdAndAuthorIdOrderByCreatedAt(articleId, viewerId)
            .forEach(comment -> visible.put(comment.id, comment));
      }
    }
    return new DiscussionView(
        views(List.copyOf(visible.values())),
        viewerId,
        viewerId != null,
        eligible,
        policy.enabled,
        policy.requireApproval,
        policy.editingWindowMinutes,
        1,
        suspended ? "suspended" : "allowed",
        privilege == null ? null : privilege.suspendedUntil(),
        viewerId != null && eligible && policy.enabled && !suspended);
  }

  private List<Comment> visibleComments(UUID articleId) {
    var approved = comments.findByArticleIdAndStateOrderByCreatedAt(articleId, "approved");
    var result = new LinkedHashMap<UUID, Comment>();
    for (var comment : approved) {
      if (comment.parentId == null) {
        result.put(comment.id, comment);
      } else {
        var parent = comments.findById(comment.parentId).orElse(null);
        if (parent != null
            && parent.articleId.equals(articleId)
            && ("approved".equals(parent.state) || "deleted".equals(parent.state))) {
          result.put(parent.id, parent);
          result.put(comment.id, comment);
        }
      }
    }
    return List.copyOf(result.values());
  }

  private boolean visibleParent(Comment comment) {
    if (comment.parentId == null) return true;
    return comments
        .findById(comment.parentId)
        .filter(
            parent ->
                parent.articleId.equals(comment.articleId)
                    && parent.parentId == null
                    && ("approved".equals(parent.state) || "deleted".equals(parent.state)))
        .isPresent();
  }

  @Override
  @Transactional(readOnly = true)
  public ModerationPage moderationPage(String state, UUID articleId, int page, int size) {
    if (state != null
        && !Set.of("pending", "approved", "rejected", "spam", "deleted").contains(state)) {
      throw new IllegalArgumentException("Unsupported comment state");
    }
    if (page < 0 || page > 100_000)
      throw new IllegalArgumentException("Page must be between 0 and 100000");
    var result = comments.queue(state, articleId, PageRequest.of(page, limit(size)));
    return new ModerationPage(
        result.stream().map(this::queueView).toList(), page, size, result.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public ModerationQueueItem moderationDetail(UUID commentId) {
    return queueView(find(commentId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ModerationQueueItem> moderationQueue(int limit) {
    int bounded = limit(limit);
    return comments
        .findByStateInOrderByCreatedAtAsc(Set.of("pending", "spam"), PageRequest.of(0, bounded))
        .stream()
        .map(this::queueView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ReportView> reportQueue(int limit) {
    return reports.findByStatusOrderByCreatedAtAsc("open", PageRequest.of(0, limit(limit))).stream()
        .map(
            report ->
                new ReportView(
                    report.id,
                    report.commentId,
                    report.reporterId,
                    report.reason,
                    report.details,
                    report.status,
                    report.createdAt))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ModerationHistoryView> moderationHistory(UUID commentId) {
    find(commentId);
    return moderation
        .findByCommentIdOrderByCreatedAtDesc(commentId, PageRequest.of(0, 100))
        .stream()
        .map(
            action ->
                new ModerationHistoryView(
                    action.id,
                    action.moderatorId,
                    action.previousState,
                    action.action,
                    action.reason,
                    action.createdAt))
        .toList();
  }

  @Override
  @Transactional
  public void suspend(
      UUID userId, Instant until, String reason, UUID moderatorId, long expectedVersion) {
    if (until != null && !until.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Suspension end must be in the future");
    }
    String safeReason = requiredReason(reason);
    var existing = privileges.findById(userId);
    version(existing.map(value -> value.version).orElse(-1L), expectedVersion);
    var privilege = existing.orElseGet(() -> new CommentingPrivilege(userId));
    privilege.suspend(until, safeReason, moderatorId);
    privileges.save(privilege);
    privilegeRecords.save(
        new CommentingPrivilegeRecord(userId, "suspended", until, safeReason, moderatorId));
    audit.record(
        moderatorId,
        "COMMENTING_SUSPENDED",
        "user",
        userId,
        Map.of("reason", safeReason, "until", until == null ? "indefinite" : until.toString()));
  }

  @Override
  @Transactional
  public void restorePrivilege(UUID userId, String reason, UUID moderatorId, long expectedVersion) {
    String safeReason = requiredReason(reason);
    var existing = privileges.findById(userId);
    version(existing.map(value -> value.version).orElse(-1L), expectedVersion);
    var privilege = existing.orElseGet(() -> new CommentingPrivilege(userId));
    privilege.restore(safeReason, moderatorId);
    privileges.save(privilege);
    privilegeRecords.save(
        new CommentingPrivilegeRecord(userId, "restored", null, safeReason, moderatorId));
    audit.record(moderatorId, "COMMENTING_RESTORED", "user", userId, Map.of("reason", safeReason));
  }

  @Override
  @Transactional(readOnly = true)
  public PrivilegeView privilege(UUID userId) {
    return privileges
        .findById(userId)
        .map(this::view)
        .orElseGet(() -> new PrivilegeView(userId, "allowed", null, null, null, Instant.EPOCH, -1));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PrivilegeHistoryView> privilegeHistory(UUID userId) {
    return privilegeRecords
        .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100))
        .stream()
        .map(
            record ->
                new PrivilegeHistoryView(
                    record.id,
                    record.action,
                    record.suspendedUntil,
                    record.reason,
                    record.moderatorId,
                    record.createdAt))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public GlobalSettingsView globalSettings() {
    return settings
        .findById(1)
        .map(this::view)
        .orElseGet(
            () ->
                new GlobalSettingsView(
                    defaults.enabled,
                    defaults.requireApproval,
                    defaults.editingWindowMinutes,
                    defaults.reviewSpamThreshold,
                    defaults.rejectSpamThreshold,
                    defaults.reportEscalationThreshold,
                    defaults.updatedAt,
                    -1));
  }

  @Override
  @Transactional
  public void updateGlobalSettings(
      GlobalSettingsCommand command, UUID moderatorId, long expectedVersion) {
    version(settings.findById(1).map(value -> value.version).orElse(-1L), expectedVersion);
    var setting =
        settings
            .findById(1)
            .orElseGet(
                () ->
                    new CommentGlobalSettings(
                        defaults.enabled,
                        defaults.requireApproval,
                        defaults.editingWindowMinutes,
                        defaults.reviewSpamThreshold,
                        defaults.rejectSpamThreshold,
                        defaults.reportEscalationThreshold));
    setting.update(
        command.enabled(),
        command.requireApproval(),
        command.editingWindowMinutes(),
        command.reviewSpamThreshold(),
        command.rejectSpamThreshold(),
        command.reportEscalationThreshold(),
        moderatorId);
    settings.save(setting);
    audit.record(moderatorId, "COMMENT_SETTINGS_UPDATED", "comment_settings", null, Map.of());
  }

  @Override
  @Transactional(readOnly = true)
  public ArticleSettingsView articleSettings(UUID articleId) {
    articles.get(articleId);
    return articleSettings
        .findById(articleId)
        .map(this::view)
        .orElseGet(() -> new ArticleSettingsView(articleId, null, null, Instant.EPOCH, -1));
  }

  @Override
  @Transactional
  public void updateArticleSettings(
      UUID articleId, ArticleSettingsCommand command, UUID moderatorId, long expectedVersion) {
    articles.get(articleId);
    version(
        articleSettings.findById(articleId).map(value -> value.version).orElse(-1L),
        expectedVersion);
    var setting =
        articleSettings.findById(articleId).orElseGet(() -> new ArticleCommentSettings(articleId));
    setting.update(command.enabledOverride(), command.requireApprovalOverride(), moderatorId);
    articleSettings.save(setting);
    audit.record(moderatorId, "ARTICLE_COMMENT_SETTINGS_UPDATED", "article", articleId, Map.of());
  }

  private EffectivePolicy policy(UUID articleId) {
    var article = articles.get(articleId);
    var global = currentSettings();
    var local = articleSettings.findById(articleId).orElse(null);
    boolean locallyEnabled = local == null || !Boolean.FALSE.equals(local.enabledOverride);
    boolean enabled =
        global.enabled
            && article.state() == ArticleState.PUBLISHED
            && article.commentsEnabled()
            && locallyEnabled;
    boolean requireApproval =
        local != null && local.requireApprovalOverride != null
            ? local.requireApprovalOverride
            : global.requireApproval;
    return new EffectivePolicy(
        enabled,
        requireApproval,
        global.editingWindowMinutes,
        global.reviewSpamThreshold,
        global.rejectSpamThreshold);
  }

  private void ensureCanComment(UUID authorId, EffectivePolicy policy) {
    if (!policy.enabled) {
      throw new IllegalStateException("Comments are not available for this article");
    }
    if (privileges
        .findById(authorId)
        .filter(value -> value.isSuspendedAt(Instant.now()))
        .isPresent()) {
      throw new IllegalStateException("Commenting privilege is suspended");
    }
  }

  private void validateParent(UUID articleId, UUID parentId) {
    if (parentId == null) {
      return;
    }
    var parent = locked(parentId);
    if (!parent.articleId.equals(articleId)
        || parent.parentId != null
        || parent.deletedAt != null
        || !"approved".equals(parent.state)) {
      throw new IllegalArgumentException("Replies require an approved top-level parent");
    }
  }

  private String stateFor(double spamScore, EffectivePolicy policy) {
    if (spamScore >= policy.rejectSpamThreshold) {
      return "spam";
    }
    if (policy.requireApproval || spamScore >= policy.reviewSpamThreshold) {
      return "pending";
    }
    return "approved";
  }

  private CommentGlobalSettings currentSettings() {
    return settings.findById(1).orElse(defaults);
  }

  private Comment find(UUID id) {
    return comments
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
  }

  private Comment locked(UUID id) {
    return comments
        .findLockedById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
  }

  private static void version(long actual, long expected) {
    if (actual != expected)
      throw new OptimisticLockingFailureException(
          "Comment resource has changed; refresh and retry");
  }

  private static String requiredReason(String reason) {
    return optional(required(reason, "Reason"), 2_000);
  }

  private ModerationQueueItem queueView(Comment comment) {
    var article = articles.get(comment.articleId);
    return new ModerationQueueItem(
        comment.id,
        comment.articleId,
        comment.authorId,
        identity.displayNames(Set.of(comment.authorId)).getOrDefault(comment.authorId, "Reader"),
        comment.body,
        comment.state,
        comment.spamScore,
        comment.spamReason,
        reports.countByCommentIdAndStatus(comment.id, "open"),
        comment.createdAt,
        comment.editedAt,
        comment.deletedByAuthor,
        comment.parentId,
        comment.deletedAt != null,
        comment.version,
        article.headline(),
        article.slug());
  }

  private List<CommentView> views(List<Comment> source) {
    var authorIds = source.stream().map(comment -> comment.authorId).distinct().toList();
    var names = new java.util.HashMap<UUID, String>();
    for (int offset = 0; offset < authorIds.size(); offset += 100) {
      names.putAll(
          identity.displayNames(
              Set.copyOf(authorIds.subList(offset, Math.min(offset + 100, authorIds.size())))));
    }
    return source.stream()
        .map(comment -> view(comment, names.getOrDefault(comment.authorId, "Reader")))
        .toList();
  }

  private CommentView view(Comment comment, String authorName) {
    return new CommentView(
        comment.id,
        comment.authorId,
        authorName,
        comment.body,
        comment.parentId,
        comment.state,
        comment.createdAt,
        comment.editedAt,
        comment.deletedByAuthor,
        comment.deletedAt != null,
        comment.version);
  }

  private PrivilegeView view(CommentingPrivilege privilege) {
    String effectiveStatus = privilege.isSuspendedAt(Instant.now()) ? "suspended" : "allowed";
    return new PrivilegeView(
        privilege.userId,
        effectiveStatus,
        privilege.suspendedUntil,
        privilege.reason,
        privilege.moderatorId,
        privilege.updatedAt,
        privilege.version);
  }

  private GlobalSettingsView view(CommentGlobalSettings setting) {
    return new GlobalSettingsView(
        setting.enabled,
        setting.requireApproval,
        setting.editingWindowMinutes,
        setting.reviewSpamThreshold,
        setting.rejectSpamThreshold,
        setting.reportEscalationThreshold,
        setting.updatedAt,
        setting.version);
  }

  private ArticleSettingsView view(ArticleCommentSettings setting) {
    return new ArticleSettingsView(
        setting.articleId,
        setting.enabledOverride,
        setting.requireApprovalOverride,
        setting.updatedAt,
        setting.version);
  }

  private static String safeBody(String body) {
    String safe = required(body, "Comment body");
    if (safe.length() > 5_000) {
      throw new IllegalArgumentException("Comment body is too long");
    }
    if (safe.contains("<") || safe.contains(">")) {
      throw new IllegalArgumentException("Comments support plain text only");
    }
    return safe;
  }

  private static String required(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private static String optional(String value, int maxLength) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException("Text exceeds maximum length");
    }
    return normalized;
  }

  private static int limit(int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Limit must be between 1 and 100");
    }
    return limit;
  }

  private record EffectivePolicy(
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold) {}
}
