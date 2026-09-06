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
import com.nsangusa.news.comments.SpamDecisionSupport;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
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

@ExtendWith(MockitoExtension.class)
class CommentApplicationServiceTests {
  @Mock ArticleService articles;
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
    when(comments.findById(comment.id)).thenReturn(Optional.of(comment));
    allowCommenting(articleId, authorId);

    assertThatThrownBy(() -> service.edit(comment.id, authorId, "Changed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("window");
  }

  @Test
  void softDeletesAnAuthorsCommentWhilePreservingItsThreadPosition() {
    UUID articleId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    var comment = comment(articleId, authorId, "Original", "approved");
    when(comments.findById(comment.id)).thenReturn(Optional.of(comment));

    service.deleteByAuthor(comment.id, authorId);

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
    when(comments.findById(comment.id)).thenReturn(Optional.of(comment));
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
    when(comments.findById(comment.id)).thenReturn(Optional.of(comment));
    when(reports.findByCommentIdAndStatus(comment.id, "open")).thenReturn(List.of(report));

    service.moderate(comment.id, "rejected", "Policy violation", moderatorId);

    var action = ArgumentCaptor.forClass(ModerationAction.class);
    verify(moderation).save(action.capture());
    assertThat(action.getValue().previousState).isEqualTo("pending");
    assertThat(action.getValue().action).isEqualTo("rejected");
    assertThat(report.status).isEqualTo("resolved");
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
