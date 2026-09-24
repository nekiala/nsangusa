package com.nsangusa.news.publication.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.integration.SystemActors;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PublicationInvariantTests {
  @Test
  void productionHumanReviewPolicyNeverPublishesFromApprovalEvent() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    var event = approval();
    when(reader.eventType("event")).thenReturn("ArticleApproved");
    when(reader.read("event", ArticleApproved.class)).thenReturn(event);
    when(articles.getLocked(event.payload().articleId()))
        .thenReturn(article(event.payload().articleId(), ArticleState.APPROVED, 0.99));

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "HUMAN_REVIEW_ALWAYS", new java.math.BigDecimal("0.95"), "", ""))
        .consume("event");

    verify(articles, never()).publish(any(), any(), any(), any());
    verify(processed).markProcessed(event.eventId(), "publication-v1");
  }

  @Test
  void confidencePolicyPublishesAnEligibleApprovedArticle() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    var event = approval();
    when(reader.eventType("event")).thenReturn("ArticleApproved");
    when(reader.read("event", ArticleApproved.class)).thenReturn(event);
    when(articles.getLocked(event.payload().articleId()))
        .thenReturn(article(event.payload().articleId(), ArticleState.APPROVED, 0.99));

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "CONFIDENCE_THRESHOLD", new java.math.BigDecimal("0.95"), "", ""))
        .consume("event");

    verify(articles)
        .publish(
            event.payload().articleId(),
            event.payload().approvedBy(),
            event.correlationId(),
            event.eventId());
  }

  @Test
  void eligibleReadyArticleIsApprovedByTheAutomationActor() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    UUID articleId = UUID.randomUUID();
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleReadyForReview",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "ready:" + articleId,
            new ArticleReadyForReview(articleId));
    when(reader.eventType("event")).thenReturn("ArticleReadyForReview");
    when(reader.read("event", ArticleReadyForReview.class)).thenReturn(event);
    when(articles.getLocked(articleId))
        .thenReturn(article(articleId, ArticleState.AWAITING_REVIEW, 0.99));

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "CONFIDENCE_THRESHOLD", new java.math.BigDecimal("0.95"), "", ""))
        .ready("event");

    verify(articles).approve(articleId, SystemActors.AUTOMATION);
    verify(processed).markProcessed(event.eventId(), "publication-policy-v1");
  }

  @ParameterizedTest
  @EnumSource(value = ArticleState.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
  void approvalEventsCannotPublishFromAnyOtherLockedState(ArticleState state) {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    var event = approval();
    when(reader.eventType("event")).thenReturn("ArticleApproved");
    when(reader.read("event", ArticleApproved.class)).thenReturn(event);
    when(articles.getLocked(event.payload().articleId()))
        .thenReturn(article(event.payload().articleId(), state, 0.99));

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "CONFIDENCE_THRESHOLD", new java.math.BigDecimal("0.95"), "", ""))
        .consume("event");

    verify(articles, never()).publish(any(), any(), any(), any());
    verify(processed).markProcessed(event.eventId(), "publication-v1");
  }

  @ParameterizedTest
  @EnumSource(value = ArticleState.class, names = "AWAITING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
  void readinessEventsCannotReapproveAnyOtherLockedState(ArticleState state) {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var articles = mock(ArticleService.class);
    UUID articleId = UUID.randomUUID();
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleReadyForReview",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "ready:" + articleId,
            new ArticleReadyForReview(articleId));
    when(reader.eventType("event")).thenReturn("ArticleReadyForReview");
    when(reader.read("event", ArticleReadyForReview.class)).thenReturn(event);
    when(articles.getLocked(articleId)).thenReturn(article(articleId, state, 0.99));

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "CONFIDENCE_THRESHOLD", new java.math.BigDecimal("0.95"), "", ""))
        .ready("event");

    verify(articles, never()).approve(any(), any());
    verify(processed).markProcessed(event.eventId(), "publication-policy-v1");
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

    new PublicationConsumer(
            reader,
            processed,
            articles,
            new PublicationPolicyEvaluator(
                "AUTOMATIC_ABOVE_THRESHOLD", new java.math.BigDecimal("0.95"), "", ""))
        .consume("event");

    verify(articles, never()).publish(any(), any(), any(), any());
    verify(processed, never()).markProcessed(any(), any());
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

  private static ArticleService.ArticleView article(
      UUID articleId, ArticleState state, double confidence) {
    return new ArticleService.ArticleView(
        articleId,
        "article",
        "Headline",
        "Summary",
        "Body",
        null,
        "world",
        java.util.Set.of("news"),
        state,
        null,
        null,
        false,
        true,
        null,
        Instant.now(),
        0,
        List.of(
            new ArticleService.SourceView(
                UUID.randomUUID(),
                "account",
                "post",
                "https://x.com/account/status/post",
                Instant.now())),
        List.of(),
        confidence);
  }
}
