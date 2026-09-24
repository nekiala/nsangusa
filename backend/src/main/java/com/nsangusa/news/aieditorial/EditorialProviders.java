package com.nsangusa.news.aieditorial;

import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EditorialProviders {
  private EditorialProviders() {}

  public interface ProviderIdentity {
    default ProviderConfiguration configuration() {
      return new ProviderConfiguration("unknown", "unknown", "unknown");
    }
  }

  public record ProviderConfiguration(
      String provider,
      String model,
      String promptVersion,
      long configurationVersion,
      String guidance,
      String secretReference) {
    public ProviderConfiguration(String provider, String model, String promptVersion) {
      this(provider, model, promptVersion, 0, "", "operator:legacy");
    }
  }

  public interface EditorialAnalysisProvider extends ProviderIdentity {
    AnalysisResult analyze(AnalysisRequest request);

    default AnalysisResult analyze(AnalysisRequest request, ProviderConfiguration configuration) {
      return analyze(request);
    }
  }

  public interface ArticleDraftProvider extends ProviderIdentity {
    ArticleDraftGenerated draft(DraftRequest request);

    default ArticleDraftGenerated draft(DraftRequest request, ProviderConfiguration configuration) {
      return draft(request);
    }
  }

  public interface ClaimExtractionProvider {
    List<Claim> extractClaims(String delimitedSourceMaterial, List<SourceReference> sources);
  }

  public interface ContentSafetyProvider extends ProviderIdentity {
    SafetyResult evaluate(String content);

    default SafetyResult evaluate(String content, ProviderConfiguration configuration) {
      return evaluate(content);
    }
  }

  public interface EmbeddingProvider {
    List<Double> embed(String content);
  }

  public record AnalysisRequest(
      UUID storyCandidateId, List<SourceReference> sources, String sourceMaterial) {}

  public record AnalysisResult(
      @NotBlank @Size(max = 20_000) String analysis,
      @NotEmpty @Size(max = 50) List<@Valid Claim> claims,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 500) String> warnings,
      @NotBlank @Size(max = 100) String provider,
      @NotBlank @Size(max = 100) String model,
      @PositiveOrZero long inputTokens,
      @PositiveOrZero long outputTokens,
      @NotBlank @Size(max = 100) String promptVersion,
      @NotNull Instant generatedAt) {
    public AnalysisResult(
        String analysis,
        List<Claim> claims,
        BigDecimal confidence,
        List<String> warnings,
        String provider,
        String model,
        long inputTokens,
        long outputTokens) {
      this(
          analysis,
          claims,
          confidence,
          warnings,
          provider,
          model,
          inputTokens,
          outputTokens,
          "editorial-v1",
          Instant.now());
    }
  }

  public record DraftRequest(
      UUID storyCandidateId,
      List<SourceReference> sources,
      String analysis,
      List<Claim> claims,
      BigDecimal confidence,
      List<String> warnings) {}

  public record SafetyResult(
      boolean allowed,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 500) String> flags,
      @NotBlank String provider,
      @NotBlank String model,
      @NotBlank String promptVersion,
      @PositiveOrZero long inputTokens,
      @PositiveOrZero long outputTokens) {
    public SafetyResult(boolean allowed, List<String> flags) {
      this(allowed, flags, "fake", "deterministic-editorial-v1", "editorial-v1", 0, 0);
    }
  }
}
