package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleContent;
import com.nsangusa.news.articles.ArticleContent.Block;
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

class StructuredArticleWorkflowTests {
  private final UUID actor = UUID.randomUUID();
  private final List<ArticleService.SourceView> sources =
      List.of(
          new ArticleService.SourceView(
              UUID.randomUUID(),
              "source",
              "123",
              "https://example.test/123",
              Instant.parse("2026-09-01T12:00:00Z")));

  @Test
  void authoritativeBlocksAreCreatedEditedRevisionedAndComparedAlongsideCanonicalBody() {
    var first = new ArticleContent(1, List.of(new Block("heading", "Original", null, null)));
    var second =
        new ArticleContent(
            1,
            List.of(
                new Block("heading", "Corrected", null, null),
                new Block("ordered_list", null, List.of("Evidence", "Attribution"), null)));
    var article = Article.manual(UUID.randomUUID(), command("Ignored supplied body", first), actor);
    var original = article.revisions.getFirst();
    assertThat(article.body).isEqualTo("Original");
    article.approve(actor);
    article.publish(actor);
    var publishedAt = article.publishedAt;
    var slug = article.slug;
    article.startCorrection(0, "Clarified the evidence.", actor);
    article.edit(0, command(null, second), actor);
    assertThat(original.snapshot.content()).isEqualTo(first);
    assertThat(original.body).isEqualTo("Original");
    var corrected = article.revisions.getLast();
    assertThat(corrected.snapshot.content()).isEqualTo(second);
    assertThat(corrected.body).isEqualTo("Corrected\n\nEvidence\nAttribution");
    assertThat(article.body).isEqualTo(corrected.body);
    assertThat(article.slug).isEqualTo(slug);
    assertThat(article.publishedAt).isEqualTo(publishedAt);

    var revisions = mock(ArticleRevisionRepository.class);
    when(revisions.findByArticleIdAndRevisionNumber(article.id, original.revisionNumber))
        .thenReturn(Optional.of(original));
    when(revisions.findByArticleIdAndRevisionNumber(article.id, corrected.revisionNumber))
        .thenReturn(Optional.of(corrected));
    var service =
        new ArticleApplicationService(
            mock(ArticleRepository.class),
            mock(DurableEventPublisher.class),
            mock(AuditService.class),
            mock(SourceIngestionService.class),
            revisions,
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    assertThat(
            service
                .compareRevisions(article.id, original.revisionNumber, corrected.revisionNumber)
                .changedFields())
        .contains("body", "content");
  }

  @Test
  void legacyArticlesHaveAReadFallbackWithoutFabricatingHistoricalSnapshots() throws Exception {
    var article = Article.manual(UUID.randomUUID(), command("First\n\nSecond", null), actor);
    article.content = null;
    var content = article.effectiveContent();
    assertThat(content.blocks()).extracting(Block::type).containsExactly("paragraph", "paragraph");
    assertThat(article.content).isNull();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var json = mapper.valueToTree(article.revisions.getFirst().snapshot);
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("content");
    var historic = mapper.treeToValue(json, ArticleService.RevisionSnapshot.class);
    assertThat(historic.content()).isNull();
    assertThat(historic.body()).isEqualTo("First\n\nSecond");
    assertThatThrownBy(() -> Article.manual(UUID.randomUUID(), command(null, null), actor))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestValidationAllowsContentOnlyAndRequiresOneContentRepresentation() throws Exception {
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    String metadata =
        """
        "headline":"Title","summary":"Summary","seoTitle":"Title","seoDescription":"Description",
        "slugSuggestion":"title","topic":"Ideas","tags":["news"],
        "sources":[{"sourcePostId":"00000000-0000-4000-8000-000000000001",
        "account":"source","postId":"123","url":"https://example.test/123","publishedAt":"2026-09-01T12:00:00Z"}],
        "commentsEnabled":true
        """;
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var missing =
          mapper.readValue("{" + metadata + "}", ArticleController.ArticleCommandRequest.class);
      assertThat(validator.validate(missing)).isNotEmpty();
      var structured =
          mapper.readValue(
              "{"
                  + metadata
                  + """
          ,"content":{"version":1,"blocks":[{"type":"paragraph","text":"Canonical"}]}}
          """,
              ArticleController.ArticleCommandRequest.class);
      assertThat(validator.validate(structured)).isEmpty();
      assertThat(Article.manual(UUID.randomUUID(), structured.toCommand(), actor).body)
          .isEqualTo("Canonical");
    }
  }

  private ArticleService.ManualArticleCommand command(String body, ArticleContent content) {
    return new ArticleService.ManualArticleCommand(
        "Title",
        "Summary",
        body,
        null,
        "SEO title",
        "SEO description",
        "title",
        "Ideas",
        Set.of("news"),
        sources,
        true,
        content);
  }
}
