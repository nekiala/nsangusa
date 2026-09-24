package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleSourceEligibilityTests {
  @Test
  void restrictedSourceBlocksPublicationBeforeStateOrEventsChange() {
    var articles = mock(ArticleRepository.class);
    var events = mock(DurableEventPublisher.class);
    var audit = mock(AuditService.class);
    var sources = mock(SourceIngestionService.class);
    UUID articleId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    var article =
        Article.manual(
            articleId,
            new ArticleService.ManualArticleCommand(
                "Headline",
                "Summary",
                "Body",
                null,
                "SEO title",
                "SEO description",
                "headline",
                "world",
                Set.of("news"),
                List.of(
                    new ArticleService.SourceView(
                        sourceId,
                        "publisher",
                        "1900000000000000000",
                        "https://x.com/publisher/status/1900000000000000000",
                        Instant.now())),
                true),
            actorId);
    article.approve(actorId);
    when(articles.findById(articleId)).thenReturn(java.util.Optional.of(article));
    doThrow(new IllegalStateException("Article contains a restricted or missing source"))
        .when(sources)
        .assertSourcesPublishable(any());
    var service =
        new ArticleApplicationService(
            articles,
            events,
            audit,
            sources,
            org.mockito.Mockito.mock(ArticleRevisionRepository.class),
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

    assertThatThrownBy(() -> service.publish(articleId, actorId, articleId, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restricted");

    assertThat(article.state).isEqualTo(ArticleState.APPROVED);
    verifyNoInteractions(events);
  }
}
