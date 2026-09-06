package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicationInvariantTests {
  @Test
  void productionHumanReviewPolicyNeverPublishesFromApprovalEvent() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    var event = approval();
    when(reader.eventType("event")).thenReturn("ArticleApproved");
    when(reader.read("event", ArticleApproved.class)).thenReturn(event);

    new PublicationConsumer(reader, processed, articles, "HUMAN_REVIEW_ALWAYS").consume("event");

    verify(articles, never()).publish(any(), any(), any(), any());
    verify(processed).markProcessed(event.eventId(), "publication-v1");
  }

  @Test
  void duplicateApprovalEventDoesNotRepeatPublicationWork() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    var event = approval();
    when(reader.eventType("event")).thenReturn("ArticleApproved");
    when(reader.read("event", ArticleApproved.class)).thenReturn(event);
    when(processed.wasProcessed(event.eventId(), "publication-v1")).thenReturn(true);

    new PublicationConsumer(reader, processed, articles, "AUTOMATIC_ABOVE_THRESHOLD")
        .consume("event");

    verify(articles, never()).publish(any(), any(), any(), any());
    verify(processed, never()).markProcessed(any(), any());
  }

  @Test
  void dueScheduleIsCompletedOnlyAfterArticlePublicationSucceeds() {
    var schedules = mock(ScheduledPublicationRepository.class);
    var articles = mock(ArticleService.class);
    var service = new PublicationApplicationService(schedules, articles);
    var schedule = new ScheduledPublication(UUID.randomUUID(), Instant.now().minusSeconds(1));
    when(schedules.findByStatusAndScheduledForLessThanEqual(
            org.mockito.ArgumentMatchers.eq("scheduled"), any(Instant.class)))
        .thenReturn(List.of(schedule));

    service.publishDue();

    verify(articles).publish(schedule.articleId, schedule.id, schedule.articleId, schedule.id);
    assertThat(schedule.status).isEqualTo("published");
  }

  @Test
  void failedScheduledPublicationRemainsRetryable() {
    var schedules = mock(ScheduledPublicationRepository.class);
    var articles = mock(ArticleService.class);
    var service = new PublicationApplicationService(schedules, articles);
    var schedule = new ScheduledPublication(UUID.randomUUID(), Instant.now().minusSeconds(1));
    when(schedules.findByStatusAndScheduledForLessThanEqual(
            org.mockito.ArgumentMatchers.eq("scheduled"), any(Instant.class)))
        .thenReturn(List.of(schedule));
    org.mockito.Mockito.doThrow(new IllegalStateException("not publishable"))
        .when(articles)
        .publish(schedule.articleId, schedule.id, schedule.articleId, schedule.id);

    assertThatThrownBy(service::publishDue)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("not publishable");
    assertThat(schedule.status).isEqualTo("scheduled");
  }

  private static EventEnvelope<ArticleApproved> approval() {
    UUID articleId = UUID.randomUUID();
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "ArticleApproved",
        1,
        articleId,
        UUID.randomUUID(),
        null,
        Instant.now(),
        "test",
        Map.of(),
        "approval:" + articleId,
        new ArticleApproved(articleId, UUID.randomUUID()));
  }
}
