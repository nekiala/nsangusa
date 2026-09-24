package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.articles.ArticleVisibilityChanged;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

class ArticleRevisionWorkflowTests {
  private final UUID actor = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();
  private final ArticleRepository repository = mock(ArticleRepository.class);
  private final ArticleRevisionRepository revisions = mock(ArticleRevisionRepository.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final ApplicationEventPublisher localEvents = mock(ApplicationEventPublisher.class);
  private final ArticleApplicationService service =
      new ArticleApplicationService(
          repository,
          events,
          mock(AuditService.class),
          mock(SourceIngestionService.class),
          revisions,
          localEvents,
          new ObjectMapper().findAndRegisterModules());

  @Test
  void snapshotsEveryEditableFieldAndApprovalWithoutChangingOldRevisions() {
    var article = article();
    var original = article.revisions.getFirst().snapshot;
    article.edit(0, command("Corrected title", "Updated context", false), actor);
    article.approve(actor);
    var approved = article.revisions.getLast().snapshot;

    assertThat(original.headline()).isEqualTo("Original");
    assertThat(original.editorialContext()).isEqualTo("Original context");
    assertThat(original.sources()).hasSize(1);
    assertThat(original.commentsEnabled()).isTrue();
    assertThat(original.approvedBy()).isNull();
    assertThat(approved.headline()).isEqualTo("Corrected title");
    assertThat(approved.editorialContext()).isEqualTo("Updated context");
    assertThat(approved.seoTitle()).isEqualTo("SEO Corrected title");
    assertThat(approved.sources().getFirst().sourcePostId()).isEqualTo(sourceId);
    assertThat(approved.commentsEnabled()).isFalse();
    assertThat(approved.state()).isEqualTo(ArticleState.APPROVED);
    assertThat(approved.approvedBy()).isEqualTo(actor);
    assertThat(approved.approvedAt()).isNotNull();
  }

  @Test
  void correctionWithdrawsAndRequiresFreshApprovalButPreservesCanonicalAndNewsletterIdentity() {
    var article = article();
    when(repository.findById(article.id)).thenReturn(Optional.of(article));
    article.approve(actor);
    service.publish(article.id, actor, article.id, null);
    String slug = article.slug;
    Instant publishedAt = article.publishedAt;

    service.startCorrection(article.id, article.version, "Corrected the opening time.", actor);

    assertThat(article.state).isEqualTo(ArticleState.AWAITING_REVIEW);
    assertThat(article.approvedBy).isNull();
    assertThat(article.revisions.getLast().reason).isEqualTo("CORRECTION_STARTED");
    assertThatThrownBy(() -> service.publish(article.id, actor, article.id, null))
        .isInstanceOf(IllegalStateException.class);
    article.edit(article.version, command("Corrected", "Updated evidence", true), actor);
    article.approve(actor);
    service.publish(article.id, actor, article.id, null);

    assertThat(article.slug).isEqualTo(slug);
    assertThat(article.publishedAt).isEqualTo(publishedAt);
    assertThat(article.correctionNote).isEqualTo("Corrected the opening time.");
    var published = ArgumentCaptor.forClass(ArticlePublished.class);
    verify(events, org.mockito.Mockito.times(2))
        .enqueue(
            eq("ArticlePublished"),
            eq(article.id),
            eq(article.id),
            any(),
            any(),
            published.capture());
    assertThat(published.getAllValues())
        .extracting(ArticlePublished::newsletterEligible)
        .containsExactly(true, false);
    verify(localEvents, org.mockito.Mockito.times(3))
        .publishEvent(new ArticleVisibilityChanged(article.id));
  }

  @Test
  void staleCorrectionCannotWithdrawThePublishedArticle() {
    var article = article();
    article.approve(actor);
    article.publish(actor);
    assertThatThrownBy(() -> article.startCorrection(1, "Correction", actor))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    assertThat(article.state).isEqualTo(ArticleState.PUBLISHED);
    assertThat(article.correctionNote).isNull();
  }

  @Test
  void scheduledContentMustBeCancelledBeforeEditing() {
    var article = article();
    article.approve(actor);
    article.schedule(actor);
    assertThatThrownBy(() -> article.edit(0, command("Changed", "Context", true), actor))
        .isInstanceOf(IllegalStateException.class);
    article.cancelSchedule(actor);
    assertThat(article.state).isEqualTo(ArticleState.APPROVED);
    article.edit(0, command("Changed", "Context", true), actor);
    assertThat(article.state).isEqualTo(ArticleState.AWAITING_REVIEW);
  }

  @Test
  void revisionComparisonIncludesSourcesMetadataAndApprovalWithBoundedPages() {
    var article = article();
    var first = article.revisions.getFirst();
    article.edit(0, command("Changed", "New context", false), actor);
    var second = article.revisions.getLast();
    when(revisions.findByArticleIdAndRevisionNumber(article.id, 1)).thenReturn(Optional.of(first));
    when(revisions.findByArticleIdAndRevisionNumber(article.id, 2)).thenReturn(Optional.of(second));
    var comparison = service.compareRevisions(article.id, 1, 2);
    assertThat(comparison.changedFields())
        .contains("headline", "editorialContext", "seoTitle", "commentsEnabled");
    assertThat(comparison.from().snapshot().headline()).isEqualTo("Original");
    when(repository.findById(article.id)).thenReturn(Optional.of(article));
    when(revisions.findByArticleId(eq(article.id), any()))
        .thenReturn(new PageImpl<>(List.of(second, first)));
    assertThat(service.revisions(article.id, 0, 20).total()).isEqualTo(2);
    assertThatThrownBy(() -> service.revisions(article.id, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Article article() {
    return Article.manual(UUID.randomUUID(), command("Original", "Original context", true), actor);
  }

  private ArticleService.ManualArticleCommand command(
      String title, String context, boolean comments) {
    return new ArticleService.ManualArticleCommand(
        title,
        "Summary",
        "Body",
        context,
        "SEO " + title,
        "SEO description",
        "canonical",
        "culture",
        Set.of("news"),
        List.of(
            new ArticleService.SourceView(
                sourceId,
                "source",
                "123",
                "https://x.com/source/status/123",
                Instant.parse("2026-09-01T12:00:00Z"))),
        comments);
  }
}
