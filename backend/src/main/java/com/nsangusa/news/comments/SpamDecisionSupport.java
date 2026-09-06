package com.nsangusa.news.comments;

import java.util.List;

public interface SpamDecisionSupport {
  SpamAssessment assess(String body);

  record SpamAssessment(double score, List<String> signals) {
    public SpamAssessment {
      if (score < 0 || score > 1) {
        throw new IllegalArgumentException("Spam score must be between 0 and 1");
      }
      signals = List.copyOf(signals);
    }

    public String reason() {
      return String.join(",", signals);
    }
  }
}
