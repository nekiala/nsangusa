package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ArticleWorkspaceTests {
  private final ArticleRepository articles = mock(ArticleRepository.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final AuditService audit = mock(AuditService.class);
  private final SourceIngestionService sources = mock(SourceIngestionService.class);
  private final ArticleApplicationService service =
      new ArticleApplicationService(
          articles,
          events,
          audit,
          sources,
          org.mockito.Mockito.mock(ArticleRevisionRepository.class),
          org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  private final UUID actor = UUID.randomUUID();

  @Test
  void manualSourcesMustMatchAuthoritativeRecords() {
    var source = source("Library");
    var spoofed =
        new ArticleService.SourceView(
            source.sourcePostId(),
            "Unrelated",
            source.postId(),
            source.url(),
            source.publishedAt());

    assertThatThrownBy(() -> service.createManual(command(List.of(spoofed)), actor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metadata");
    verifyNoInteractions(articles, events, audit);

    service.createManual(command(List.of(source)), actor);
    var saved = ArgumentCaptor.forClass(Article.class);
    verify(articles).save(saved.capture());
    assertThat(saved.getValue().sources)
        .singleElement()
        .satisfies(item -> assertThat(item.sourcePostId).isEqualTo(source.sourcePostId()));
  }

  @Test
  void sourceEditsPreserveSelectedRowsAndReplaceOnlyRemovedReferences() {
    var first = source("Library");
    var second = source("Council");
    var third = source("Reporter");
    var article = Article.manual(UUID.randomUUID(), command(List.of(first, second)), actor);
    UUID preservedRow = article.sources.get(1).id;
    when(articles.findById(article.id)).thenReturn(Optional.of(article));

    service.edit(article.id, 0, command(List.of(second, third)), actor);

    assertThat(article.sources)
        .extracting(item -> item.sourcePostId)
        .containsExactly(second.sourcePostId(), third.sourcePostId());
    assertThat(article.sources.getFirst().id).isEqualTo(preservedRow);
    assertThat(article.revisions).hasSize(2);
  }

  @Test
  void duplicateOrMissingSourcesAreRejected() {
    var source = source("Library");
    assertThatThrownBy(() -> service.createManual(command(List.of(source, source)), actor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(() -> service.createManual(command(List.of()), actor))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(articles);
  }

  @Test
  void queuesUseBoundedPagesAndExposeCurrentMetadata() {
    var article = Article.manual(UUID.randomUUID(), command(List.of(source("Library"))), actor);
    when(articles.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(article)));

    var page = service.list(null, 0, 20);

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items())
        .singleElement()
        .satisfies(item -> assertThat(item.seoTitle()).isEqualTo("SEO title"));
    assertThatThrownBy(() -> service.list(null, -1, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.list(null, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ArticleService.SourceView source(String handle) {
    UUID id = UUID.randomUUID();
    String postId = "990000000000000001";
    String url = "https://x.com/" + handle + "/status/" + postId;
    Instant time = Instant.parse("2026-09-01T12:00:00Z");
    var stored = mock(SourceIngestionService.SourceView.class);
    when(stored.handle()).thenReturn(handle);
    when(stored.postId()).thenReturn(postId);
    when(stored.canonicalUrl()).thenReturn(url);
    when(stored.publishedAt()).thenReturn(time);
    when(sources.getSource(id)).thenReturn(stored);
    return new ArticleService.SourceView(id, handle, postId, url, time);
  }

  private ArticleService.ManualArticleCommand command(List<ArticleService.SourceView> selected) {
    return new ArticleService.ManualArticleCommand(
        "Headline",
        "Summary",
        "Body",
        "Context",
        "SEO title",
        "SEO description",
        "headline",
        "culture",
        Set.of("news"),
        selected,
        true);
  }
}
