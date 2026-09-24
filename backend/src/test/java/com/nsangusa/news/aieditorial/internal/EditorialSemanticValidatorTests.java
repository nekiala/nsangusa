package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import com.nsangusa.news.sourceingestion.SourceIngestionService.SourceView;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EditorialSemanticValidatorTests {
  private final SourceIngestionService ingestion = mock(SourceIngestionService.class);
  private final EditorialSemanticValidator validator =
      new EditorialSemanticValidator(
          ingestion, Validation.buildDefaultValidatorFactory().getValidator());
  private final UUID sourceId = UUID.randomUUID();
  private final Instant publishedAt = Instant.parse("2026-09-01T12:00:00Z");
  private final SourceReference source =
      new SourceReference(
          sourceId, "account", "123", "https://x.com/account/status/123", publishedAt);

  @Test
  void acceptsOnlyStoredEligibleSourceIdentityAndRebuildsCurrentPermittedMaterial() {
    when(ingestion.getSource(sourceId)).thenReturn(sourceView("Corrected permitted material"));
    var material = validator.authorizedSources(List.of(source));
    assertThat(validator.sourceMaterial(List.of(source), material))
        .contains("Corrected permitted material", source.url());
    verify(ingestion).assertSourcesPublishable(java.util.Set.of(sourceId));
    var forged =
        new SourceReference(
            sourceId, "account", "123", "https://attacker.test/instructions", publishedAt);
    assertThatThrownBy(() -> validator.authorizedSources(List.of(forged)))
        .hasMessageContaining("unsupported_sources");
  }

  @Test
  void rejectsExcludedSourcesAndDuplicateReferences() {
    when(ingestion.getSource(sourceId)).thenReturn(sourceView("permitted material"));
    assertThatThrownBy(() -> validator.authorizedSources(List.of(source, source)))
        .hasMessageContaining("unsupported_sources");
    doThrow(new IllegalStateException("restricted source"))
        .when(ingestion)
        .assertSourcesPublishable(any());
    assertThatThrownBy(() -> validator.authorizedSources(List.of(source)))
        .hasMessageContaining("restricted source");
  }

  @Test
  void rejectsForeignSupportingIdsAndNeverTreatsXAsVerified() {
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(
                        new Claim("A report", "REPORTED", List.of(UUID.randomUUID())),
                        "Analysis",
                        "0.6"),
                    Map.of(sourceId, "A report")))
        .hasMessageContaining("unsupported_sources");
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(
                        new Claim("A report", "VERIFIED", List.of(sourceId)), "Analysis", "0.6"),
                    Map.of(sourceId, "A report")))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void boundsConfidenceAndNestedClaims() {
    var claim = new Claim("A report", "UNVERIFIED", List.of(sourceId));
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(claim, "Analysis", "1.01"), Map.of(sourceId, "A report")))
        .isInstanceOf(ConstraintViolationException.class);
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(new Claim("", "DISPUTED", List.of(sourceId)), "Analysis", "0.6"),
                    Map.of(sourceId, "A report")))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void checksQuotesAgainstTheirSupportingSourcesInsteadOfTrustingModelAttribution() {
    var claim = new Claim("The source said “unavailable quote”.", "REPORTED", List.of(sourceId));
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(claim, "Analysis", "0.6"),
                    Map.of(sourceId, "A source reports a change.")))
        .hasMessageContaining("unsupported_quotation");
    var supported = new Claim("The source said “reports a change”.", "REPORTED", List.of(sourceId));
    validator.analysis(
        analysis(supported, "Analysis", "0.6"), Map.of(sourceId, "A source reports a change."));
    assertThatThrownBy(
            () ->
                validator.analysis(
                    analysis(supported, "The witness said \"invented a fact\".", "0.6"),
                    Map.of(sourceId, "reports a change")))
        .hasMessageContaining("unsupported_quotation");
  }

  private AnalysisResult analysis(Claim claim, String text, String confidence) {
    return new AnalysisResult(
        text,
        List.of(claim),
        new BigDecimal(confidence),
        List.of("human-review-required"),
        "fake",
        "deterministic-editorial-v1",
        10,
        10);
  }

  @Test
  void draftsCannotAddClaimsOrCopyLongSourcePassages() {
    String sourceText = "A source describes developments in a public matter. ".repeat(10);
    UUID storyId = UUID.randomUUID();
    var claims = List.of(new Claim("A reported development", "REPORTED", List.of(sourceId)));
    var fake = new FakeEditorialProvider();
    var draft =
        fake.draft(
            new DraftRequest(
                storyId,
                List.of(source),
                "analysis",
                claims,
                new BigDecimal("0.6"),
                List.of("human-review-required")));
    validator.draft(draft, storyId, List.of(source), claims, Map.of(sourceId, sourceText));
    var otherClaims = List.of(new Claim("A different development", "REPORTED", List.of(sourceId)));
    assertThatThrownBy(
            () ->
                validator.draft(
                    draft, storyId, List.of(source), otherClaims, Map.of(sourceId, sourceText)))
        .hasMessageContaining("unsupported_claims");
    var copied =
        new ArticleDraftGenerated(
            draft.storyCandidateId(),
            draft.headline(),
            draft.summary(),
            sourceText,
            draft.editorialContext(),
            draft.seoTitle(),
            draft.seoDescription(),
            draft.slugSuggestion(),
            draft.tags(),
            draft.topic(),
            draft.sources(),
            draft.claims(),
            draft.confidence(),
            draft.uncertaintyNotes(),
            draft.safetyFlags(),
            true,
            draft.imagePrompt(),
            draft.imageAltText(),
            draft.socialPreviewText(),
            draft.provider(),
            draft.model(),
            draft.promptVersion(),
            draft.generatedAt());
    assertThatThrownBy(
            () ->
                validator.draft(
                    copied, storyId, List.of(source), claims, Map.of(sourceId, sourceText)))
        .hasMessageContaining("excessive_source_copying");
  }

  private SourceView sourceView(String text) {
    return new SourceView(
        sourceId,
        UUID.randomUUID(),
        source.postId(),
        "account-id",
        source.account(),
        source.url(),
        text,
        source.publishedAt(),
        Instant.now(),
        "active",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        0);
  }
}
