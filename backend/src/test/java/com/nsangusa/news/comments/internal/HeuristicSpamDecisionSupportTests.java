package com.nsangusa.news.comments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeuristicSpamDecisionSupportTests {
  private final HeuristicSpamDecisionSupport support = new HeuristicSpamDecisionSupport();

  @Test
  void scoresCommonSpamSignalsWithoutRejectingOrdinaryText() {
    assertThat(support.assess("A thoughtful response to the article.").score()).isZero();

    var assessment =
        support.assess(
            "BUY NOW AND CLICK HERE https://spam.example https://spam.example/free!!!!!!!!");

    assertThat(assessment.score()).isGreaterThanOrEqualTo(0.5);
    assertThat(assessment.signals()).contains("spam-phrases:2", "links:2");
  }
}
