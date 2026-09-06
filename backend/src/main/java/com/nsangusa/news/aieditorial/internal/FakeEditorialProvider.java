package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ClaimExtractionProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.EmbeddingProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake", matchIfMissing = true)
class FakeEditorialProvider
    implements EditorialAnalysisProvider,
        ArticleDraftProvider,
        ClaimExtractionProvider,
        ContentSafetyProvider,
        EmbeddingProvider {

  @Override
  public AnalysisResult analyze(AnalysisRequest request) {
    var claims = extractClaims(request.sourceMaterial(), request.sources());
    return new AnalysisResult(
        "A single permitted X source reports the event. Independent confirmation is not available.",
        claims,
        new BigDecimal("0.62"),
        List.of("single-source", "independent-confirmation-required"),
        "fake",
        "deterministic-editorial-v1",
        request.sourceMaterial().length() / 4L,
        48);
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request) {
    SourceReference source = request.sources().getFirst();
    String subject = request.claims().getFirst().text().replaceAll("\\s+", " ").trim();
    String headline = subject.length() > 72 ? subject.substring(0, 69) + "..." : subject;
    return new ArticleDraftGenerated(
        request.storyCandidateId(),
        headline,
        "A monitored account reported a developing story; independent verification remains pending.",
        "The account @"
            + source.account()
            + " reported: "
            + subject
            + "\n\nThis report currently relies on one source. Readers should treat details as unverified until corroborated.",
        "Editorial context: publication requires human review and should be updated as additional evidence becomes available.",
        headline,
        "A concise report based on a monitored source, with uncertainty and attribution disclosed.",
        headline.toLowerCase(Locale.ROOT),
        Set.of("developing", "source-report"),
        "general",
        request.sources(),
        request.claims(),
        request.confidence(),
        request.warnings(),
        List.of(),
        true,
        "A restrained newspaper-style editorial illustration using deep navy ink on warm white paper, symbolic and non-photorealistic, no text, no logos, no identifiable private people",
        "Editorial illustration representing a developing report",
        headline + " — a developing report requiring verification.",
        "fake",
        "deterministic-editorial-v1",
        "editorial-v1",
        Instant.now());
  }

  @Override
  public List<Claim> extractClaims(String sourceMaterial, List<SourceReference> sources) {
    return List.of(
        new Claim(
            sourceMaterial.replaceAll("\\s+", " ").trim(),
            "REPORTED_SINGLE_SOURCE",
            sources.stream().map(SourceReference::sourcePostId).toList()));
  }

  @Override
  public SafetyResult evaluate(String content) {
    boolean injection =
        content.toLowerCase(Locale.ROOT).contains("ignore previous instructions")
            || content.toLowerCase(Locale.ROOT).contains("system prompt");
    return new SafetyResult(true, injection ? List.of("prompt-injection-attempt") : List.of());
  }

  @Override
  public List<Double> embed(String content) {
    return List.of((double) content.length(), (double) content.hashCode());
  }
}
