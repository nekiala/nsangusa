package com.nsangusa.news.aieditorial;

import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class EditorialProviders {
  private EditorialProviders() {}

  public interface EditorialAnalysisProvider {
    AnalysisResult analyze(AnalysisRequest request);
  }

  public interface ArticleDraftProvider {
    ArticleDraftGenerated draft(DraftRequest request);
  }

  public interface ClaimExtractionProvider {
    List<Claim> extractClaims(String delimitedSourceMaterial, List<SourceReference> sources);
  }

  public interface ContentSafetyProvider {
    SafetyResult evaluate(String content);
  }

  public interface EmbeddingProvider {
    List<Double> embed(String content);
  }

  public record AnalysisRequest(
      UUID storyCandidateId, List<SourceReference> sources, String sourceMaterial) {}

  public record AnalysisResult(
      String analysis,
      List<Claim> claims,
      BigDecimal confidence,
      List<String> warnings,
      String provider,
      String model,
      long inputTokens,
      long outputTokens) {}

  public record DraftRequest(
      UUID storyCandidateId,
      List<SourceReference> sources,
      String analysis,
      List<Claim> claims,
      BigDecimal confidence,
      List<String> warnings) {}

  public record SafetyResult(boolean allowed, List<String> flags) {}
}
