package com.nsangusa.news.comments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.comments.SpamDecisionSupport;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.identity.IdentityService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class CommentApplicationServiceTests {
  @Mock ArticleService articles;
  @Mock IdentityService identity;
  @Mock CommentRepository comments;
  @Mock ModerationActionRepository moderation;
  @Mock CommentReportRepository reports;
  @Mock CommentingPrivilegeRepository privileges;
  @Mock CommentingPrivilegeRecordRepository privilegeRecords;
  @Mock CommentGlobalSettingsRepository settings;
  @Mock ArticleCommentSettingsRepository articleSettings;
  @Mock SpamDecisionSupport spam;
  @Mock DurableEventPublisher events;
  @Mock AuditService audit;

  private CommentApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new CommentApplicationService(
            articles,
            identity,
            comments,
            moderation,
            reports,
            privileges,
            privilegeRecords,
            settings,
            articleSettings,
            spam,
            events,
            audit,
            true,
            false,
            15,
            0.55,
            0.9,
            3);
  }

  @Test
  void sendsSuspiciousCommentsToReviewAndRecordsSpamEvidence() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    allowCommenting(articleId, authorId);
    when(spam.assess("Buy now"))
        .thenReturn(new SpamDecisionSupport.SpamAssessment(0.6, List.of("phrase")));
    when(comments.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

    UUID id = service.submit(articleId, authorId, " Buy now ", null);

    var saved = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(saved.capture());
    assertThat(id).isEqualTo(saved.getValue().id);
    assertThat(saved.getValue().state).isEqualTo("pending");
    assertThat(saved.getValue().spamScore).isEqualTo(0.6);
    assertThat(saved.getValue().spamReason).isEqualTo("phrase");
  }

  @Test
  void blocksSuspendedUsers() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    when(settings.findById(1)).thenReturn(Optional.empty());
    when(articleSettings.findById(articleId)).thenReturn(Optional.empty());
    var privilege = new CommentingPrivilege(authorId);
    privilege.suspend(null, "abuse", UUID.randomUUID());
    when(privileges.findById(authorId)).thenReturn(Optional.of(privilege));

    assertThatThrownBy(() -> service.submit(articleId, authorId, "Hello", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("suspended");
    verify(comments, never()).save(any());
  }

  @Test
  void enforcesArticleOverridesWithoutBypassingTheArticleModule() {
    UUID articleId = UUID.randomUUID();
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    when(settings.findById(1)).thenReturn(Optional.empty());
    var local = new ArticleCommentSettings(articleId);
    local.update(false, null, UUID.randomUUID());
    when(articleSettings.findById(articleId)).thenReturn(Optional.of(local));

    assertThat(service.approvedForArticle(articleId)).isEmpty();
    verify(comments, never()).findByArticleIdAndStateOrderByCreatedAt(any(), any());
  }

  @Test
  void rejectsEditsOutsideTheConfiguredWindow() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    var comment = comment(articleId, authorId, "Original", "approved");
    comment.createdAt = Instant.now().minus(16, ChronoUnit.MINUTES);
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    allowCommenting(articleId, authorId);

    assertThatThrownBy(() -> service.edit(comment.id, authorId, "Changed", 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("window");
  }

  @Test
  void softDeletesAnAuthorsCommentWhilePreservingItsThreadPosition() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    var comment = comment(articleId, authorId, "Original", "approved");
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));

    service.deleteByAuthor(comment.id, authorId, 0);

    assertThat(comment.body).isEqualTo("[deleted]");
    assertThat(comment.state).isEqualTo("approved");
    assertThat(comment.deletedByAuthor).isTrue();
    assertThat(comment.deletedAt).isNotNull();
  }

  @Test
  void escalatesMultiplyReportedApprovedComments() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    UUID reporterId = UUID.randomUUID();
    var comment = comment(articleId, authorId, "Comment", "approved");
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    when(reports.existsByCommentIdAndReporterId(comment.id, reporterId)).thenReturn(false);
    when(reports.save(any(CommentReport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(reports.countByCommentIdAndStatus(comment.id, "open")).thenReturn(3L);
    when(settings.findById(1)).thenReturn(Optional.empty());

    UUID reportId = service.report(comment.id, reporterId, "abuse", "details");

    assertThat(reportId).isNotNull();
    assertThat(comment.state).isEqualTo("pending");
  }

  @Test
  void moderationCreatesHistoryAndResolvesOpenReports() {
    UUID articleId = UUID.randomUUID();
    var comment = comment(articleId, UUID.randomUUID(), "Comment", "pending");
    UUID moderatorId = UUID.randomUUID();
    var report = new CommentReport(comment.id, UUID.randomUUID(), "abuse", null);
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    when(reports.findByCommentIdAndStatus(comment.id, "open")).thenReturn(List.of(report));

    service.moderate(comment.id, "rejected", "Policy violation", moderatorId, 0);

    var action = ArgumentCaptor.forClass(ModerationAction.class);
    verify(moderation).save(action.capture());
    assertThat(action.getValue().previousState).isEqualTo("pending");
    assertThat(action.getValue().action).isEqualTo("rejected");
    assertThat(report.status).isEqualTo("resolved");
    var moderated =
        ArgumentCaptor.forClass(com.nsangusa.news.integration.NewsEvents.CommentModerated.class);
    verify(events)
        .enqueue(
            org.mockito.ArgumentMatchers.eq("CommentModerated"),
            org.mockito.ArgumentMatchers.eq(comment.id),
            org.mockito.ArgumentMatchers.eq(comment.id),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("comment-moderated:" + action.getValue().id),
            moderated.capture());
    assertThat(moderated.getValue())
        .isEqualTo(
            new com.nsangusa.news.integration.NewsEvents.CommentModerated(
                comment.id,
                articleId,
                "pending",
                "rejected",
                moderatorId,
                action.getValue().createdAt));
  }

  @Test
  void deniesEditingAndDeletingAnotherReadersComment() {
    var comment = comment(UUID.randomUUID(), UUID.randomUUID(), "Original", "approved");
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    assertThatThrownBy(() -> service.edit(comment.id, UUID.randomUUID(), "Changed", 0))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.deleteByAuthor(comment.id, UUID.randomUUID(), 0))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(comment.body).isEqualTo("Original");
  }

  @Test
  void editingWithinWindowReassessesApprovalAndAuditsWithoutStoringTextInAudit() {
    var comment = comment(UUID.randomUUID(), UUID.randomUUID(), "Original", "approved");
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    allowCommenting(comment.articleId, comment.authorId);
    when(spam.assess("Changed"))
        .thenReturn(new SpamDecisionSupport.SpamAssessment(0.7, List.of("link")));
    service.edit(comment.id, comment.authorId, "Changed", 0);
    assertThat(comment.state).isEqualTo("pending");
    assertThat(comment.editedAt).isNotNull();
    verify(audit)
        .record(comment.authorId, "COMMENT_EDITED", "comment", comment.id, java.util.Map.of());
  }

  @Test
  void rejectsStaleEditsAndModerationBeforeChangingState() {
    var comment = comment(UUID.randomUUID(), UUID.randomUUID(), "Original", "approved");
    comment.version = 5;
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    assertThatThrownBy(() -> service.edit(comment.id, comment.authorId, "Changed", 4))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> service.moderate(comment.id, "spam", "Spam", UUID.randomUUID(), 4))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(comment.state).isEqualTo("approved");
  }

  @Test
  void moderationRequiresReasonsIncludingApprovalAndCannotResurrectDeletedText() {
    var comment = comment(UUID.randomUUID(), UUID.randomUUID(), "Original", "pending");
    assertThatThrownBy(() -> service.moderate(comment.id, "approved", " ", UUID.randomUUID(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    service.moderate(comment.id, "deleted", "Personal data", UUID.randomUUID(), 0);
    assertThat(comment.deletedAt).isNotNull();
    assertThat(comment.body).isEqualTo("[deleted by moderator]");
    assertThatThrownBy(
            () -> service.moderate(comment.id, "approved", "Restore", UUID.randomUUID(), 0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void disabledGlobalPolicyOverridesEnabledArticleAndHidesDiscussion() {
    UUID articleId = UUID.randomUUID();
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    when(settings.findById(1))
        .thenReturn(Optional.of(new CommentGlobalSettings(false, false, 15, .55, .9, 3)));
    var local = new ArticleCommentSettings(articleId);
    local.enabledOverride = true;
    when(articleSettings.findById(articleId)).thenReturn(Optional.of(local));
    assertThat(service.discussion(articleId, null, false).enabled()).isFalse();
    assertThatThrownBy(() -> service.submit(articleId, UUID.randomUUID(), "Hello", null))
        .isInstanceOf(IllegalStateException.class);
    verify(comments, never()).save(any());
  }

  @Test
  void unpublishedArticleNeverAcceptsOrExposesComments() {
    UUID articleId = UUID.randomUUID();
    var published = article(articleId, true);
    var unpublished =
        new ArticleService.ArticleView(
            published.id(),
            published.slug(),
            published.headline(),
            published.summary(),
            published.body(),
            null,
            published.topic(),
            published.tags(),
            ArticleState.UNPUBLISHED,
            null,
            null,
            false,
            true,
            published.publishedAt(),
            Instant.now(),
            1,
            List.of(),
            List.of(),
            .9);
    when(articles.get(articleId)).thenReturn(unpublished);
    assertThat(service.approvedForArticle(articleId)).isEmpty();
    assertThat(service.discussion(articleId, UUID.randomUUID(), true).canComment()).isFalse();
    assertThatThrownBy(() -> service.submit(articleId, UUID.randomUUID(), "Hello", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void onlyVisibleTopLevelParentsCanReceiveReplies() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    allowCommenting(articleId, authorId);
    var parent = comment(articleId, UUID.randomUUID(), "Parent", "approved");
    when(comments.findLockedById(parent.id)).thenReturn(Optional.of(parent));
    when(spam.assess("Reply")).thenReturn(new SpamDecisionSupport.SpamAssessment(0, List.of()));
    when(comments.save(any())).thenAnswer(call -> call.getArgument(0));
    service.submit(articleId, authorId, "Reply", parent.id);
    var captured = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(captured.capture());
    assertThat(captured.getValue().parentId).isEqualTo(parent.id);
    parent.parentId = UUID.randomUUID();
    assertThatThrownBy(() -> service.submit(articleId, authorId, "Reply", parent.id))
        .isInstanceOf(IllegalArgumentException.class);
    parent.parentId = null;
    parent.state = "pending";
    assertThatThrownBy(() -> service.submit(articleId, authorId, "Reply", parent.id))
        .isInstanceOf(IllegalArgumentException.class);
    parent.state = "approved";
    parent.deletedAt = Instant.now();
    assertThatThrownBy(() -> service.submit(articleId, authorId, "Reply", parent.id))
        .isInstanceOf(IllegalArgumentException.class);
    parent.deletedAt = null;
    parent.articleId = UUID.randomUUID();
    assertThatThrownBy(() -> service.submit(articleId, authorId, "Reply", parent.id))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void moderatedParentHidesRepliesButDeletedParentPreservesTombstone() {
    UUID articleId = UUID.randomUUID();
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    var parent = comment(articleId, UUID.randomUUID(), "Parent", "pending");
    var reply = new Comment(articleId, UUID.randomUUID(), "Reply", parent.id, "approved", 0, null);
    when(comments.findByArticleIdAndStateOrderByCreatedAt(articleId, "approved"))
        .thenReturn(List.of(reply));
    when(comments.findById(parent.id)).thenReturn(Optional.of(parent));
    assertThat(service.approvedForArticle(articleId)).isEmpty();
    parent.state = "deleted";
    parent.deletedAt = Instant.now();
    parent.body = "[deleted by moderator]";
    var visible = service.approvedForArticle(articleId);
    assertThat(visible).hasSize(2);
    assertThat(visible.getFirst().deleted()).isTrue();
    assertThat(visible.getLast().parentId()).isEqualTo(parent.id);
  }

  @Test
  void discussionIncludesOnlyViewersPrivateCommentsAndEffectivePrivilege() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    var own = comment(articleId, authorId, "Awaiting approval", "pending");
    when(comments.findByArticleIdAndAuthorIdOrderByCreatedAt(articleId, authorId))
        .thenReturn(List.of(own));
    var privilege = new CommentingPrivilege(authorId);
    privilege.suspend(null, "Repeated abuse", UUID.randomUUID());
    when(privileges.findById(authorId)).thenReturn(Optional.of(privilege));
    var discussion = service.discussion(articleId, authorId, true);
    assertThat(discussion.comments())
        .extracting(CommentService.CommentView::id)
        .containsExactly(own.id);
    assertThat(discussion.canComment()).isFalse();
    assertThat(discussion.privilegeStatus()).isEqualTo("suspended");
  }

  @Test
  void privilegeExpirationAndRestorationAreVersionedAndAudited() {
    UUID userId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    var privilege = new CommentingPrivilege(userId);
    privilege.suspend(Instant.now().minusSeconds(10), "Expired", actor);
    when(privileges.findById(userId)).thenReturn(Optional.of(privilege));
    assertThat(service.privilege(userId).status()).isEqualTo("allowed");
    service.suspend(userId, null, "Repeated abuse", actor, 0);
    assertThat(service.privilege(userId).status()).isEqualTo("suspended");
    assertThatThrownBy(() -> service.restorePrivilege(userId, "Restored", actor, 4))
        .isInstanceOf(OptimisticLockingFailureException.class);
    service.restorePrivilege(userId, "Appeal upheld", actor, 0);
    assertThat(service.privilege(userId).status()).isEqualTo("allowed");
    verify(privilegeRecords, org.mockito.Mockito.times(2)).save(any());
  }

  @Test
  void stalePolicyAndUnboundedQueueRequestsAreRejected() {
    when(settings.findById(1)).thenReturn(Optional.empty());
    assertThat(service.globalSettings().version()).isEqualTo(-1);
    var command = new CommentService.GlobalSettingsCommand(true, true, 10, .5, .9, 3);
    assertThatThrownBy(() -> service.updateGlobalSettings(command, UUID.randomUUID(), 0))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> service.moderationPage("pending", null, -1, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.moderationPage("pending", null, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.moderationPage("invalid", null, 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsReportsWhenAnArticleIsDisabledOrParentHidden() {
    UUID articleId = UUID.randomUUID();
    var comment = comment(articleId, UUID.randomUUID(), "Comment", "approved");
    when(comments.findLockedById(comment.id)).thenReturn(Optional.of(comment));
    when(articles.get(articleId)).thenReturn(article(articleId, false));
    assertThatThrownBy(() -> service.report(comment.id, UUID.randomUUID(), "abuse", null))
        .isInstanceOf(IllegalStateException.class);
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    comment.parentId = UUID.randomUUID();
    assertThatThrownBy(() -> service.report(comment.id, UUID.randomUUID(), "abuse", null))
        .isInstanceOf(IllegalStateException.class);
    verify(reports, never()).save(any());
  }

  private void allowCommenting(UUID articleId, UUID authorId) {
    when(articles.get(articleId)).thenReturn(article(articleId, true));
    when(settings.findById(1)).thenReturn(Optional.empty());
    when(articleSettings.findById(articleId)).thenReturn(Optional.empty());
    when(privileges.findById(authorId)).thenReturn(Optional.empty());
  }

  private static Comment comment(UUID articleId, UUID authorId, String body, String state) {
    return new Comment(articleId, authorId, body, null, state, 0, "");
  }

  private static ArticleService.ArticleView article(UUID id, boolean commentsEnabled) {
    return new ArticleService.ArticleView(
        id,
        "slug",
        "Headline",
        "Summary",
        "Body",
        null,
        "topic",
        Set.of("tag"),
        ArticleState.PUBLISHED,
        null,
        null,
        false,
        commentsEnabled,
        Instant.now(),
        Instant.now(),
        1,
        List.of(),
        List.of(),
        0.9);
  }
}
