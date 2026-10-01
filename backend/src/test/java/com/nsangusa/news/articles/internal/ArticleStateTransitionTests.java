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
    var generated = draft(true);
    var article = Article.fromDraft(UUID.randomUUID(), generated);

    assertThat(article.state).isEqualTo(ArticleState.DRAFTING);
    assertThat(article.storyCandidateId).isEqualTo(generated.storyCandidateId());
    assertThat(article.revisions.getFirst().aiGenerationResult).isEqualTo(generated);
    assertThat(article.content.blocks())
        .singleElement()
        .satisfies(
            block -> {
              assertThat(block.type()).isEqualTo("paragraph");
              assertThat(block.text()).isEqualTo(generated.body());
            });
    assertThat(article.revisions.getFirst().snapshot.content()).isEqualTo(article.content);
    assertThatThrownBy(() -> article.publish(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    UUID generationId = UUID.randomUUID();
    article.imageCandidateReady(generationId);
    assertThat(article.state).isEqualTo(ArticleState.DRAFTING);
    assertThat(article.imageApproved(generationId, "articles/test/hero.svg", "Safe illustration"))
        .isTrue();
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
  void approvedFallbackProvenanceIsPreservedInTheArticleAndRevision() {
    var article = Article.fromDraft(UUID.randomUUID(), draft(true));
    UUID generationId = UUID.randomUUID();
    article.imageCandidateReady(generationId);

    assertThat(article.imageApproved(generationId, "articles/fallback/hero.png", "Neutral", false))
        .isTrue();

    assertThat(article.state).isEqualTo(ArticleState.AWAITING_REVIEW);
    assertThat(article.generatedImage).isFalse();
    assertThat(article.imageApprovalRequired).isFalse();
    assertThat(article.revisions.getLast().snapshot.generatedImage()).isFalse();
    assertThat(article.revisions.getLast().snapshot.approvedImageGenerationId())
        .isEqualTo(generationId);
  }

  @Test
  void lateCandidateEventForTheApprovedImageLeavesTheArticleUnchanged() {
    var article = Article.fromDraft(UUID.randomUUID(), draft(true));
    UUID fallback = UUID.randomUUID();
    // The approval consumer can apply its event before the candidate consumer applies its own.
    article.imageApproved(fallback, "articles/fallback/hero.png", "Neutral", false);
    var updatedAt = article.updatedAt;
    int revisions = article.revisions.size();

    article.imageCandidateReady(fallback);

    assertThat(article.pendingImageGenerationId).isNull();
    assertThat(article.approvedImageGenerationId).isEqualTo(fallback);
    assertThat(article.imageApprovalRequired).isFalse();
    assertThat(article.state).isEqualTo(ArticleState.AWAITING_REVIEW);
    assertThat(article.updatedAt).isEqualTo(updatedAt);
    assertThat(article.revisions).hasSize(revisions);
  }

  @Test
  void regenerationDoesNotReplaceApprovedImageUntilExplicitApproval() {
    var article = Article.fromDraft(UUID.randomUUID(), draft(true));
    UUID originalGeneration = UUID.randomUUID();
    article.imageCandidateReady(originalGeneration);
    article.imageApproved(originalGeneration, "articles/original/hero.svg", "Original");
    article.approve(UUID.randomUUID());

    UUID replacementGeneration = UUID.randomUUID();
    article.imageCandidateReady(replacementGeneration);

    assertThat(article.heroObjectKey).isEqualTo("articles/original/hero.svg");
    assertThat(article.approvedImageGenerationId).isEqualTo(originalGeneration);
    assertThat(article.pendingImageGenerationId).isEqualTo(replacementGeneration);
    assertThat(article.state).isEqualTo(ArticleState.APPROVED);

    assertThat(
            article.imageApproved(
                replacementGeneration, "articles/replacement/hero.svg", "Replacement"))
        .isFalse();
    assertThat(article.heroObjectKey).isEqualTo("articles/replacement/hero.svg");
    assertThat(article.approvedImageGenerationId).isEqualTo(replacementGeneration);
    assertThat(article.pendingImageGenerationId).isNull();
    assertThat(article.state).isEqualTo(ArticleState.APPROVED);
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

  @Test
  void editingAnAiDraftCannotBypassInitialImageApproval() {
    UUID editorId = UUID.randomUUID();
    var article = Article.fromDraft(UUID.randomUUID(), draft(true));
    var command =
        new com.nsangusa.news.articles.ArticleService.ManualArticleCommand(
            "Edited headline",
            "Edited summary",
            "Edited body",
            null,
            "Edited SEO title",
            "Edited SEO description",
            "edited-headline",
            "general",
            Set.of("news"),
            List.of(
                new com.nsangusa.news.articles.ArticleService.SourceView(
                    article.sources.getFirst().sourcePostId,
                    "account",
                    "post",
                    "https://example.test/post",
                    Instant.now())),
            true);

    article.edit(0, command, editorId);

    assertThatThrownBy(() -> article.approve(editorId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("image");
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
