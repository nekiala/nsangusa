package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.publication.PublicationService.PolicyView;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PublicationPolicyEvaluator {
  private final Policy policy;
  private final BigDecimal confidenceThreshold;
  private final Map<String, BigDecimal> topicRules;
  private final Set<String> approvedSourceAccounts;

  PublicationPolicyEvaluator(
      @Value("${news.publication.policy:HUMAN_REVIEW_ALWAYS}") String policy,
      @Value("${news.publication.automatic-confidence-threshold:0.95}")
          BigDecimal confidenceThreshold,
      @Value("${news.publication.topic-rules:}") String topicRules,
      @Value("${news.publication.approved-source-accounts:}") String approvedSourceAccounts) {
    this.policy = Policy.parse(policy);
    this.confidenceThreshold = requireThreshold(confidenceThreshold);
    this.topicRules = parseTopicRules(topicRules);
    this.approvedSourceAccounts = parseAccounts(approvedSourceAccounts);
    if (this.policy == Policy.TOPIC_RULES && this.topicRules.isEmpty()) {
      throw new IllegalStateException("TOPIC_RULES requires at least one configured topic rule");
    }
  }

  boolean shouldAutomaticallyPublish(ArticleView article) {
    if (policy == Policy.HUMAN_REVIEW_ALWAYS || policy == Policy.DRAFT_GENERATION_ONLY) {
      return false;
    }
    if (article.state() != ArticleState.AWAITING_REVIEW
        && article.state() != ArticleState.APPROVED) {
      return false;
    }
    if (article.warnings() == null
        || !article.warnings().isEmpty()
        || article.imageApprovalRequired()
        || article.sources() == null
        || article.sources().isEmpty()
        || !Double.isFinite(article.confidence())
        || article.confidence() < 0
        || article.confidence() > 1) {
      return false;
    }
    BigDecimal confidence = BigDecimal.valueOf(article.confidence());
    return switch (policy) {
      case HUMAN_REVIEW_ALWAYS, DRAFT_GENERATION_ONLY -> false;
      case CONFIDENCE_THRESHOLD -> confidence.compareTo(confidenceThreshold) >= 0;
      case APPROVED_SOURCE_ONLY ->
          confidence.compareTo(confidenceThreshold) >= 0
              && article.sources().stream()
                  .allMatch(
                      source ->
                          source != null
                              && approvedSourceAccounts.contains(
                                  normalizeAccount(source.account())));
      case TOPIC_RULES -> {
        BigDecimal threshold =
            article.topic() == null
                ? null
                : topicRules.get(article.topic().toLowerCase(Locale.ROOT));
        yield threshold != null && confidence.compareTo(threshold) >= 0;
      }
    };
  }

  Policy policy() {
    return policy;
  }

  PolicyView view() {
    String explanation =
        switch (policy) {
          case HUMAN_REVIEW_ALWAYS ->
              "Automation never approves or publishes; an editor must explicitly publish or schedule an approved article.";
          case DRAFT_GENERATION_ONLY ->
              "Generation may create drafts but cannot automatically approve or publish them. Explicit human approval, publication and scheduling remain available.";
          case CONFIDENCE_THRESHOLD ->
              "Automatic publication requires the confidence threshold, no warnings and an approved image when required.";
          case APPROVED_SOURCE_ONLY ->
              "Automatic publication requires every source account to be explicitly allowlisted, the confidence threshold and no warnings.";
          case TOPIC_RULES ->
              "Automatic publication requires an explicitly configured topic threshold and no warnings.";
        };
    return new PolicyView(
        policy.name(), confidenceThreshold, topicRules, approvedSourceAccounts, true, explanation);
  }

  private static Set<String> parseAccounts(String configuredAccounts) {
    if (configuredAccounts == null || configuredAccounts.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configuredAccounts.split(","))
        .map(PublicationPolicyEvaluator::normalizeAccount)
        .filter(account -> !account.isBlank())
        .peek(
            account -> {
              if (!account.matches("[a-z0-9_]{1,100}")) {
                throw new IllegalArgumentException(
                    "Approved source accounts must be explicit account handles");
              }
            })
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalizeAccount(String account) {
    return account == null ? "" : account.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
  }

  private static Map<String, BigDecimal> parseTopicRules(String configuredRules) {
    if (configuredRules == null || configuredRules.isBlank()) {
      return Map.of();
    }
    try {
      return Arrays.stream(configuredRules.split(","))
          .map(String::trim)
          .filter(rule -> !rule.isBlank())
          .map(rule -> rule.split("=", 2))
          .collect(
              Collectors.toUnmodifiableMap(
                  parts -> {
                    if (parts.length != 2 || parts[0].isBlank()) {
                      throw new IllegalArgumentException("Invalid topic publication rule");
                    }
                    return parts[0].trim().toLowerCase(Locale.ROOT);
                  },
                  parts -> requireThreshold(new BigDecimal(parts[1].trim()))));
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid topic publication threshold", exception);
    }
  }

  private static BigDecimal requireThreshold(BigDecimal threshold) {
    if (threshold == null
        || threshold.compareTo(BigDecimal.ZERO) < 0
        || threshold.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(
          "Publication confidence threshold must be between 0 and 1");
    }
    return threshold;
  }

  enum Policy {
    HUMAN_REVIEW_ALWAYS,
    CONFIDENCE_THRESHOLD,
    APPROVED_SOURCE_ONLY,
    DRAFT_GENERATION_ONLY,
    TOPIC_RULES;

    static Policy parse(String value) {
      String normalized =
          value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
      if ("AUTOMATIC_ABOVE_THRESHOLD".equals(normalized)) {
        return CONFIDENCE_THRESHOLD;
      }
      if ("AUTOMATIC_TOPIC_RULES".equals(normalized)) {
        return TOPIC_RULES;
      }
      if ("APPROVED_SOURCES_ONLY".equals(normalized)) {
        return APPROVED_SOURCE_ONLY;
      }
      try {
        return valueOf(normalized);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Unsupported publication policy " + value, exception);
      }
    }
  }
}
