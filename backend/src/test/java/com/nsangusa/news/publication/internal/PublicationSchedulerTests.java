package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PublicationSchedulerTests {
  private final ScheduledPublicationRepository schedules =
      mock(ScheduledPublicationRepository.class);
  private final ArticleService articles = mock(ArticleService.class);
  private final AuditService audit = mock(AuditService.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @Test
  void dueSchedulePublishesAsOriginalResponsibleActorOnce() {
    var schedule = due();
    executor().execute(schedule.id, Instant.now());
    verify(articles)
        .publish(schedule.articleId, schedule.scheduledBy, schedule.articleId, schedule.id);
    assertThat(schedule.status).isEqualTo("published");
    assertThat(schedule.attemptCount).isEqualTo(1);
    assertThat(schedule.completedAt).isNotNull();
    when(schedules.claimDue(eq(schedule.id), any())).thenReturn(Optional.empty());
    executor().execute(schedule.id, Instant.now());
    verify(articles, times(1)).publish(any(), any(), any(), any());
  }

  @Test
  void invalidSourceFailureRollsBackThenPersistsAnActionableError() {
    var schedule = due();
    doThrow(new IllegalStateException("Source is no longer eligible"))
        .when(articles)
        .publish(any(), any(), any(), any());
    executor().execute(schedule.id, Instant.now());
    assertThat(schedule.status).isEqualTo("failed");
    assertThat(schedule.lastError).contains("Source is no longer eligible");
    assertThat(schedule.attemptCount).isEqualTo(1);
    assertThat(schedule.completedAt).isNull();
    verify(transactions).rollback(any());
    verify(transactions).commit(any());
    verify(audit)
        .record(
            eq(schedule.scheduledBy),
            eq("PUBLICATION_SCHEDULE_FAILED"),
            eq("publication_schedule"),
            eq(schedule.id),
            any());
  }

  @Test
  void approvedVersionMismatchNeverPublishesEvenIfArticleIsScheduledAgain() {
    var schedule = due();
    when(articles.getLocked(schedule.articleId))
        .thenReturn(
            PublicationApplicationServiceTests.article(
                schedule.articleId, ArticleState.SCHEDULED, 5));
    executor().execute(schedule.id, Instant.now());
    assertThat(schedule.status).isEqualTo("failed");
    assertThat(schedule.lastError).contains("article version changed");
    verify(articles, never()).publish(any(), any(), any(), any());
  }

  @Test
  void failureRecordingCannotOverwriteAConcurrentEditorChange() {
    var schedule = due();
    when(articles.getLocked(schedule.articleId))
        .thenAnswer(
            ignored -> {
              schedule.version++;
              throw new IllegalStateException("Source is no longer eligible");
            });
    executor().execute(schedule.id, Instant.now());
    assertThat(schedule.status).isEqualTo("scheduled");
    assertThat(schedule.lastError).isNull();
  }

  @Test
  void pollingContinuesAfterOneFailureAndUsesBoundedCandidates() {
    var executor = mock(ScheduledPublicationExecutor.class);
    UUID failed = UUID.randomUUID();
    UUID healthy = UUID.randomUUID();
    when(schedules.dueIds(any(), eq(PageRequest.of(0, 20)))).thenReturn(List.of(failed, healthy));
    doThrow(new IllegalStateException("failure storage unavailable"))
        .when(executor)
        .execute(eq(failed), any());
    new PublicationScheduler(schedules, executor, 20).publishDue();
    verify(executor).execute(eq(healthy), any());
    assertThatThrownBy(() -> new PublicationScheduler(schedules, executor, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ScheduledPublication due() {
    var schedule =
        new ScheduledPublication(
            UUID.randomUUID(), Instant.now().minusSeconds(10), UUID.randomUUID(), 4);
    when(schedules.claimDue(eq(schedule.id), any())).thenReturn(Optional.of(schedule));
    when(schedules.lockById(schedule.id)).thenReturn(Optional.of(schedule));
    when(articles.getLocked(schedule.articleId))
        .thenReturn(
            PublicationApplicationServiceTests.article(
                schedule.articleId, ArticleState.SCHEDULED, 4));
    when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    return schedule;
  }

  private ScheduledPublicationExecutor executor() {
    return new ScheduledPublicationExecutor(schedules, articles, audit, transactions);
  }
}
