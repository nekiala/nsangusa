package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleStateTransitionTests {
  @Test
  void requiresImageReviewAndApprovalBeforePublication() {
    var article = Article.fromDraft(UUID.randomUUID(), draft(true));

    assertThat(article.state).isEqualTo(ArticleState.DRAFTING);
    assertThatThrownBy(() -> article.publish(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    article.imageReady("articles/test/hero.svg", "Safe illustration");
    assertThat(article.state).isEqualTo(ArticleState.AWAITING_REVIEW);

    article.approve(UUID.randomUUID());
    article.publish(UUID.randomUUID());
    assertThat(article.state).isEqualTo(ArticleState.PUBLISHED);

    article.unpublish(UUID.randomUUID());
    assertThat(article.state).isEqualTo(ArticleState.UNPUBLISHED);
    assertThat(article.commentsEnabled).isFalse();

    article.restore(UUID.randomUUID());
    assertThat(article.state).isEqualTo(ArticleState.PUBLISHED);
    assertThat(article.commentsEnabled).isFalse();
    assertThat(article.unpublishedAt).isNull();
  }

  @Test
  void rejectsStaleEditsBeforeMutatingArticleContent() {
    UUID editorId = UUID.randomUUID();
    var command =
        new com.nsangusa.news.articles.ArticleService.ManualArticleCommand(
            "Headline",
            "Summary",
            "Body",
            "Context",
            "SEO title",
            "SEO description",
            "headline",
            "general",
            Set.of("news"),
            List.of(
                new com.nsangusa.news.articles.ArticleService.SourceView(
                    UUID.randomUUID(),
                    "account",
                    "post",
                    "https://example.test/post",
                    Instant.now())),
            true);
    var article = Article.manual(UUID.randomUUID(), command, editorId);

    assertThatThrownBy(() -> article.edit(1, command, editorId))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    assertThat(article.headline).isEqualTo("Headline");
    assertThat(article.revisions).hasSize(1);
  }

  @Test
  void rejectsAiAttemptToBypassHumanReview() {
    assertThatThrownBy(() -> Article.fromDraft(UUID.randomUUID(), draft(false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("human review");
  }

  private ArticleDraftGenerated draft(boolean humanReview) {
    UUID sourceId = UUID.randomUUID();
    return new ArticleDraftGenerated(
        UUID.randomUUID(),
        "Verified headline",
        "Summary",
        "Attributed body",
        "Context",
        "SEO title",
        "SEO description",
        "verified-headline",
        Set.of("news"),
        "general",
        List.of(
            new SourceReference(
                sourceId,
                "account",
                "post-1",
                "https://x.com/account/status/post-1",
                Instant.now())),
        List.of(new Claim("Reported claim", "REPORTED", List.of(sourceId))),
        new BigDecimal("0.60"),
        List.of("single-source"),
        List.of(),
        humanReview,
        "Symbolic illustration",
        "Symbolic editorial illustration",
        "Preview",
        "fake",
        "fake-model",
        "v1",
        Instant.now());
  }
}
