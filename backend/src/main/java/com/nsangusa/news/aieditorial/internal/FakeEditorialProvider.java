package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ClaimExtractionProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.EmbeddingProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test", "staging"})
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake")
class FakeEditorialProvider
    implements EditorialAnalysisProvider,
        ArticleDraftProvider,
        ClaimExtractionProvider,
        ContentSafetyProvider,
        EmbeddingProvider {
  private AiAdministrationApplicationService administration;

  @Autowired
  void useAdministration(AiAdministrationApplicationService administration) {
    this.administration = administration;
  }

  @Override
  public ProviderConfiguration configuration() {
    return administration == null
        ? new ProviderConfiguration("fake", "deterministic-editorial-v1", "editorial-v1")
        : administration.snapshot();
  }

  @Override
  public AnalysisResult analyze(AnalysisRequest request) {
    return analyze(request, configuration());
  }

  @Override
  public AnalysisResult analyze(AnalysisRequest request, ProviderConfiguration configuration) {
    var claims = extractClaims(request.sourceMaterial(), request.sources());
    return new AnalysisResult(
        "A single permitted X source reports the event. Independent confirmation is not available.",
        claims,
        new BigDecimal("0.62"),
        List.of("single-source", "independent-confirmation-required"),
        "fake",
        configuration.model(),
        request.sourceMaterial().length() / 4L,
        48,
        configuration.promptVersion(),
        Instant.now());
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request) {
    return draft(request, configuration());
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request, ProviderConfiguration configuration) {
    SourceReference source = request.sources().getFirst();
    String subject = request.claims().getFirst().text().replaceAll("\\s+", " ").trim();
    String headline = subject.length() > 72 ? subject.substring(0, 69) + "..." : subject;
    String slug =
        headline.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
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
        slug.isBlank() ? "developing-report" : slug,
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
        configuration.model(),
        configuration.promptVersion(),
        Math.max(1, request.analysis().length() / 4L),
        Math.max(1, (subject.length() + 350L) / 4L),
        Instant.now());
  }

  @Override
  public List<Claim> extractClaims(String sourceMaterial, List<SourceReference> sources) {
    String text =
        sourceMaterial
            .lines()
            .filter(line -> !line.startsWith("Source @"))
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(sourceMaterial)
            .replaceAll("\\s+", " ")
            .trim();
    text = text.substring(0, Math.min(180, text.length()));
    return List.of(
        new Claim(text, "REPORTED", sources.stream().map(SourceReference::sourcePostId).toList()));
  }

  @Override
  public SafetyResult evaluate(String content) {
    return evaluate(content, configuration());
  }

  @Override
  public SafetyResult evaluate(String content, ProviderConfiguration configuration) {
    boolean injection =
        content.toLowerCase(Locale.ROOT).contains("ignore previous instructions")
            || content.toLowerCase(Locale.ROOT).contains("system prompt");
    return new SafetyResult(
        true,
        injection ? List.of("prompt-injection-attempt") : List.of(),
        "fake",
        configuration.model(),
        configuration.promptVersion(),
        0,
        0);
  }

  @Override
  public List<Double> embed(String content) {
    return List.of((double) content.length(), (double) content.hashCode());
  }
}
