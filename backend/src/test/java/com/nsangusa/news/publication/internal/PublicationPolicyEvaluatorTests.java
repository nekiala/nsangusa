package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicationPolicyEvaluatorTests {
  @Test
  void confidencePolicyRequiresThresholdAndNoWarnings() {
    var policy =
        new PublicationPolicyEvaluator("CONFIDENCE_THRESHOLD", new BigDecimal("0.90"), "", "");

    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.91, List.of()))).isTrue();
    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.89, List.of()))).isFalse();
    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.99, List.of("single-source"))))
        .isFalse();
  }

  @Test
  void topicRulesApplyOnlyToExplicitlyConfiguredTopics() {
    var policy =
        new PublicationPolicyEvaluator(
            "TOPIC_RULES", new BigDecimal("0.99"), "world=0.90,technology=0.97", "");

    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.91, List.of()))).isTrue();
    assertThat(policy.shouldAutomaticallyPublish(article("technology", 0.95, List.of()))).isFalse();
    assertThat(policy.shouldAutomaticallyPublish(article("sports", 1.0, List.of()))).isFalse();
  }

  @Test
  void approvedSourcePolicyRequiresEveryExplicitHandleAndThreshold() {
    var policy =
        new PublicationPolicyEvaluator(
            "APPROVED_SOURCE_ONLY", new BigDecimal("0.95"), "", " @PUBLISHER, Other ");
    assertThat(
            policy.shouldAutomaticallyPublish(
                article("world", 0.99, List.of(), "@Publisher", "OTHER")))
        .isTrue();
    assertThat(
            policy.shouldAutomaticallyPublish(
                article("world", 0.99, List.of(), "publisher", "unknown")))
        .isFalse();
    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.94, List.of(), "publisher")))
        .isFalse();
    assertThat(
            policy.shouldAutomaticallyPublish(
                article("world", 0.99, List.of("review-required"), "publisher")))
        .isFalse();
    assertThat(policy.shouldAutomaticallyPublish(article("world", 0.99, List.of(), new String[0])))
        .isFalse();
    assertThat(policy.view().approvedSourceAccounts())
        .containsExactlyInAnyOrder("publisher", "other");
    assertThat(
            new PublicationPolicyEvaluator("approved-source-only", new BigDecimal("0.95"), "", "")
                .shouldAutomaticallyPublish(article("world", 1, List.of())))
        .isFalse();
  }

  @Test
  void humanReviewAndDraftOnlyNeverAutomaticallyPublishButAllowExplicitHumanActions() {
    for (String name : List.of("HUMAN_REVIEW_ALWAYS", "DRAFT_GENERATION_ONLY")) {
      var policy = new PublicationPolicyEvaluator(name, BigDecimal.ZERO, "", "publisher");
      assertThat(policy.shouldAutomaticallyPublish(article("world", 1, List.of()))).isFalse();
      assertThat(policy.view().humanPublicationAllowed()).isTrue();
    }
  }

  @Test
  void automationFailsClosedForInvalidConfidenceAndConfiguration() {
    var policy = new PublicationPolicyEvaluator("CONFIDENCE_THRESHOLD", BigDecimal.ZERO, "", "");
    for (double confidence : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
      assertThat(policy.shouldAutomaticallyPublish(article("world", confidence, List.of())))
          .isFalse();
    }
    assertThatThrownBy(
            () -> new PublicationPolicyEvaluator("APPROVED_SOURCE_ONLY", BigDecimal.ONE, "", "*"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PublicationPolicyEvaluator(
                    "CONFIDENCE_THRESHOLD", new BigDecimal("1.1"), "", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PublicationPolicyEvaluator("TOPIC_RULES", BigDecimal.ONE, "", ""))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ArticleService.ArticleView article(
      String topic, double confidence, List<String> warnings) {
    return article(topic, confidence, warnings, "publisher");
  }

  private static ArticleService.ArticleView article(
      String topic, double confidence, List<String> warnings, String... accounts) {
    UUID articleId = UUID.randomUUID();
    return new ArticleService.ArticleView(
        articleId,
        "article",
        "Headline",
        "Summary",
        "Body",
        null,
        topic,
        Set.of("news"),
        ArticleState.AWAITING_REVIEW,
        null,
        null,
        false,
        true,
        null,
        Instant.now(),
        0,
        java.util.Arrays.stream(accounts)
            .map(
                account ->
                    new ArticleService.SourceView(
                        UUID.randomUUID(),
                        account,
                        "1900000000000000000",
                        "https://x.com/publisher/status/1900000000000000000",
                        Instant.now()))
            .toList(),
        warnings,
        confidence);
  }
}
