package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleScheduled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class PublicationApplicationServiceTests {
  private final ScheduledPublicationRepository schedules =
      mock(ScheduledPublicationRepository.class);
  private final ArticleService articles = mock(ArticleService.class);
  private final AuditService audit = mock(AuditService.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final PublicationApplicationService service =
      new PublicationApplicationService(
          schedules,
          articles,
          audit,
          new PublicationPolicyEvaluator("HUMAN_REVIEW_ALWAYS", new BigDecimal("0.95"), "", ""),
          events);

  @Test
  void creatingScheduleCapturesFlushedArticleVersionAndResponsibleActor() {
    UUID articleId = UUID.randomUUID();
    UUID editorId = UUID.randomUUID();
    Instant publishAt = Instant.now().plusSeconds(300);
    when(articles.get(articleId)).thenReturn(article(articleId, ArticleState.SCHEDULED, 9));

    UUID id = service.schedule(articleId, publishAt, editorId);

    var order = inOrder(articles);
    order.verify(articles).markScheduled(articleId, editorId);
    order.verify(articles).get(articleId);
    var saved = ArgumentCaptor.forClass(ScheduledPublication.class);
    verify(schedules).saveAndFlush(saved.capture());
    assertThat(saved.getValue().id).isEqualTo(id);
    assertThat(saved.getValue().articleVersion).isEqualTo(9);
    assertThat(saved.getValue().scheduledBy).isEqualTo(editorId);
    verify(audit)
        .record(
            eq(editorId),
            eq("PUBLICATION_SCHEDULE_CREATED"),
            eq("publication_schedule"),
            eq(id),
            any());
    var scheduled = ArgumentCaptor.forClass(ArticleScheduled.class);
    verify(events)
        .enqueue(
            eq("ArticleScheduled"),
            eq(articleId),
            eq(articleId),
            eq(null),
            eq("article-scheduled:" + id + ":0"),
            scheduled.capture());
    assertThat(scheduled.getValue())
        .isEqualTo(new ArticleScheduled(articleId, id, publishAt, 9L, editorId));
  }

  @Test
  void rejectsMissingPastOrDuplicateScheduleBeforeChangingArticle() {
    UUID articleId = UUID.randomUUID();
    UUID editorId = UUID.randomUUID();
    assertThatThrownBy(() -> service.schedule(articleId, null, editorId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.schedule(articleId, Instant.now().minusSeconds(1), editorId))
        .isInstanceOf(IllegalArgumentException.class);
    when(schedules.existsByArticleIdAndStatusIn(articleId, List.of("scheduled", "failed")))
        .thenReturn(true);
    assertThatThrownBy(() -> service.schedule(articleId, Instant.now().plusSeconds(60), editorId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active");
    verifyNoInteractions(articles);
  }

  @Test
  void reschedulingFailureRetainsOriginalActorApprovedVersionAndLastError() {
    var schedule = existing();
    schedule.status = "failed";
    schedule.lastError = "Source temporarily ineligible";
    UUID otherEditor = UUID.randomUUID();
    Instant previous = schedule.scheduledFor;
    Instant replacement = previous.plusSeconds(300);
    when(articles.getLocked(schedule.articleId))
        .thenReturn(article(schedule.articleId, ArticleState.SCHEDULED, 4));
    doAnswer(
            ignored -> {
              schedule.version++;
              return null;
            })
        .when(schedules)
        .flush();

    var result = service.reschedule(schedule.id, 0, replacement, otherEditor);

    assertThat(result.status()).isEqualTo("scheduled");
    assertThat(result.publishAt()).isEqualTo(replacement);
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.articleVersion()).isEqualTo(4);
    assertThat(result.scheduledBy()).isEqualTo(schedule.scheduledBy);
    assertThat(result.updatedBy()).isEqualTo(otherEditor);
    assertThat(result.lastError()).isEqualTo("Source temporarily ineligible");
    verify(audit)
        .record(
            eq(otherEditor),
            eq("PUBLICATION_SCHEDULE_CHANGED"),
            eq("publication_schedule"),
            eq(schedule.id),
            org.mockito.ArgumentMatchers.argThat(
                metadata -> previous.toString().equals(metadata.get("previousPublishAt"))));
  }

  @Test
  void staleScheduleOrChangedArticleCannotBeRescheduled() {
    var schedule = existing();
    assertThatThrownBy(
            () -> service.reschedule(schedule.id, 1, schedule.scheduledFor, UUID.randomUUID()))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(articles);
    when(articles.getLocked(schedule.articleId))
        .thenReturn(article(schedule.articleId, ArticleState.SCHEDULED, 5));
    assertThatThrownBy(
            () -> service.reschedule(schedule.id, 0, schedule.scheduledFor, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("article version changed");
    verify(schedules, never()).flush();
  }

  @Test
  void cancellationUsesVersionGuardAndRestoresOnlyScheduledArticles() {
    var schedule = existing();
    UUID editor = UUID.randomUUID();
    when(articles.getLocked(schedule.articleId))
        .thenReturn(article(schedule.articleId, ArticleState.SCHEDULED, 4));
    assertThatThrownBy(() -> service.cancel(schedule.id, 2, editor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var result = service.cancel(schedule.id, 0, editor);
    assertThat(result.status()).isEqualTo("cancelled");
    assertThat(result.completedAt()).isNotNull();
    verify(articles).cancelSchedule(schedule.articleId, editor);
    verify(audit)
        .record(
            eq(editor),
            eq("PUBLICATION_SCHEDULE_CANCELLED"),
            eq("publication_schedule"),
            eq(schedule.id),
            any());
    assertThatThrownBy(() -> service.cancel(schedule.id, 0, editor))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cancellationDoesNotReapproveAnEditedOrRestrictedArticle() {
    var schedule = existing();
    when(articles.getLocked(schedule.articleId))
        .thenReturn(article(schedule.articleId, ArticleState.AWAITING_REVIEW, 5));
    assertThat(service.cancel(schedule.id, 0, UUID.randomUUID()).status()).isEqualTo("cancelled");
    verify(articles, never()).cancelSchedule(any(), any());
  }

  @Test
  void inventoryIsBoundedAndFiltersStatusAndArticle() {
    var schedule = existing();
    when(schedules.inventory(eq("failed"), eq(schedule.articleId), any()))
        .thenReturn(new PageImpl<>(List.of(schedule)));
    var result = service.list("FAILED", schedule.articleId, 0, 10);
    assertThat(result.items()).hasSize(1);
    assertThat(result.total()).isEqualTo(1);
    var page = ArgumentCaptor.forClass(Pageable.class);
    verify(schedules).inventory(eq("failed"), eq(schedule.articleId), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(10);
    assertThatThrownBy(() -> service.list(null, null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.list(null, null, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.list("unknown", null, 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ScheduledPublication existing() {
    var schedule =
        new ScheduledPublication(
            UUID.randomUUID(), Instant.now().plusSeconds(300), UUID.randomUUID(), 4);
    when(schedules.lockById(schedule.id)).thenReturn(Optional.of(schedule));
    return schedule;
  }

  static ArticleService.ArticleView article(UUID id, ArticleState state, long version) {
    return new ArticleService.ArticleView(
        id,
        "article",
        "Headline",
        "Summary",
        "Body",
        null,
        "world",
        Set.of("news"),
        state,
        null,
        null,
        false,
        true,
        null,
        Instant.now(),
        version,
        List.of(),
        List.of(),
        1);
  }
}
