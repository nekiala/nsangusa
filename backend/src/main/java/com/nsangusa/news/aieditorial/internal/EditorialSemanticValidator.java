package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class EditorialSemanticValidator {
  private static final Pattern QUOTATION =
      Pattern.compile("[\"“]([^\"”]+)[\"”]|«([^»]+)»|(?<!\\w)'([^'\\n]{3,})'(?!\\w)");
  private final SourceIngestionService ingestion;
  private final Validator validator;

  EditorialSemanticValidator(SourceIngestionService ingestion, Validator validator) {
    this.ingestion = ingestion;
    this.validator = validator;
  }

  Map<UUID, String> authorizedSources(List<SourceReference> references) {
    if (references == null || references.isEmpty() || references.size() > 20) {
      throw new AiProviderException("unsupported_sources");
    }
    Map<UUID, String> material = new LinkedHashMap<>();
    for (var reference : references) {
      validate(reference);
      var source = ingestion.getSource(reference.sourcePostId());
      if (source == null
          || !Objects.equals(reference.postId(), source.postId())
          || !Objects.equals(reference.url(), source.canonicalUrl())
          || !reference.account().replaceFirst("^@", "").equalsIgnoreCase(source.handle())
          || !Objects.equals(reference.publishedAt(), source.publishedAt())
          || source.permittedText() == null
          || source.permittedText().isBlank()
          || material.putIfAbsent(reference.sourcePostId(), source.permittedText()) != null) {
        throw new AiProviderException("unsupported_sources");
      }
    }
    ingestion.assertSourcesPublishable(material.keySet());
    return material;
  }

  String sourceMaterial(List<SourceReference> references, Map<UUID, String> material) {
    var text = new StringBuilder();
    for (var reference : references) {
      text.append("Source @")
          .append(reference.account())
          .append(" (")
          .append(reference.url())
          .append("):\n")
          .append(material.get(reference.sourcePostId()))
          .append("\n\n");
      if (text.length() > 60_000) {
        throw new AiProviderException("request_too_large");
      }
    }
    return text.toString().strip();
  }

  void analysis(AnalysisResult result, Map<UUID, String> sources) {
    validate(result);
    claims(result.claims(), sources);
    quotations(List.of(result.analysis()), sources.values());
  }

  void draft(
      ArticleDraftGenerated draft,
      UUID storyId,
      List<SourceReference> sources,
      List<Claim> originalClaims,
      Map<UUID, String> material) {
    validate(draft);
    if (!storyId.equals(draft.storyCandidateId())
        || !new HashSet<>(sources).equals(new HashSet<>(draft.sources()))
        || sources.size() != draft.sources().size()) {
      throw new AiProviderException("unsupported_sources");
    }
    claims(draft.claims(), material);
    if (!claimKeys(originalClaims).equals(claimKeys(draft.claims()))) {
      throw new AiProviderException("unsupported_claims");
    }
    quotations(
        java.util.stream.Stream.of(
                draft.headline(),
                draft.summary(),
                draft.body(),
                draft.editorialContext(),
                draft.seoTitle(),
                draft.seoDescription(),
                draft.socialPreviewText(),
                draft.imageAltText(),
                draft.imagePrompt())
            .filter(Objects::nonNull)
            .toList(),
        material.values());
    quotations(EditorialWorkflowConsumer.translatedText(draft), material.values());
    limitCopying(draft.body(), material.values());
  }

  void claims(List<Claim> claims, Map<UUID, String> sources) {
    if (claims == null || claims.isEmpty() || claims.size() > 50) {
      throw new AiProviderException("unsupported_claims");
    }
    for (var claim : claims) {
      validate(claim);
      if (!sources.keySet().containsAll(claim.supportingSourceIds())
          || new HashSet<>(claim.supportingSourceIds()).size()
              != claim.supportingSourceIds().size()) {
        throw new AiProviderException("unsupported_sources");
      }
      quotations(
          List.of(claim.text()), claim.supportingSourceIds().stream().map(sources::get).toList());
    }
  }

  void validate(Object value) {
    if (value == null) {
      throw new AiProviderException("malformed_output");
    }
    var violations = validator.validate(value);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException("AI output did not satisfy the contract", violations);
    }
  }

  private static Set<String> claimKeys(List<Claim> claims) {
    return claims.stream()
        .map(
            claim ->
                claim.classification()
                    + "\n"
                    + claim.text()
                    + "\n"
                    + claim.supportingSourceIds().stream().sorted().toList())
        .collect(java.util.stream.Collectors.toSet());
  }

  private static void quotations(List<String> text, java.util.Collection<String> sources) {
    for (String content : text) {
      var matcher = QUOTATION.matcher(content);
      while (matcher.find()) {
        String quote =
            matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (sources.stream().noneMatch(source -> normalize(source).contains(normalize(quote)))) {
          throw new AiProviderException("unsupported_quotation");
        }
      }
    }
  }

  private static String normalize(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  private static void limitCopying(String body, java.util.Collection<String> sources) {
    String normalized = normalize(body);
    for (String source : sources) {
      String original = normalize(source);
      for (int start = 0; start + 240 <= original.length(); start++) {
        if (normalized.contains(original.substring(start, start + 240))) {
          throw new AiProviderException("excessive_source_copying");
        }
      }
    }
  }
}
