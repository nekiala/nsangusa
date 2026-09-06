package com.nsangusa.news.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NewsEvents {
  private NewsEvents() {}

  public record XPostDiscovered(
      @NotNull UUID monitoredAccountId,
      @NotBlank String postId,
      @NotBlank String accountId,
      @NotBlank String handle,
      @NotBlank String canonicalUrl,
      @NotBlank @Size(max = 10_000) String permittedText,
      @NotNull Instant publishedAt) {}

  public record XPostNormalized(
      @NotNull UUID sourcePostId,
      @NotBlank String postId,
      @NotBlank String handle,
      @NotBlank String canonicalUrl,
      @NotBlank String normalizedText,
      @NotNull Instant publishedAt,
      @NotEmpty Set<String> topics) {}

  public record StoryAnalysisRequested(
      @NotNull UUID storyCandidateId,
      @NotEmpty List<SourceReference> sources,
      @NotBlank String sourceMaterial) {}

  public record ArticleDraftRequested(
      @NotNull UUID storyCandidateId,
      @NotEmpty List<SourceReference> sources,
      @NotBlank String analysis,
      @NotEmpty List<Claim> claims,
      @NotNull BigDecimal confidence,
      @NotEmpty List<String> warnings) {}

  public record ArticleDraftGenerated(
      @NotNull UUID storyCandidateId,
      @NotBlank String headline,
      @NotBlank String summary,
      @NotBlank String body,
      String editorialContext,
      @NotBlank String seoTitle,
      @NotBlank String seoDescription,
      @NotBlank String slugSuggestion,
      @NotEmpty Set<String> tags,
      @NotBlank String topic,
      @NotEmpty List<SourceReference> sources,
      @NotEmpty List<Claim> claims,
      @NotNull BigDecimal confidence,
      @NotEmpty List<String> uncertaintyNotes,
      @NotNull List<String> safetyFlags,
      boolean humanReviewRequired,
      @NotBlank String imagePrompt,
      @NotBlank String imageAltText,
      @NotBlank String socialPreviewText,
      @NotBlank String provider,
      @NotBlank String model,
      @NotBlank String promptVersion,
      @NotNull Instant generatedAt) {}

  public record ArticleImageRequested(
      @NotNull UUID articleId, @NotBlank String prompt, @NotBlank String altText) {}

  public record ArticleImageGenerated(
      @NotNull UUID articleId,
      @NotBlank String objectKey,
      @NotBlank String altText,
      @NotBlank String provider,
      @NotBlank String model,
      @NotNull Instant generatedAt) {}

  public record ArticleReadyForReview(@NotNull UUID articleId) {}

  public record ArticleApproved(@NotNull UUID articleId, @NotNull UUID approvedBy) {}

  public record ArticlePublished(
      @NotNull UUID articleId,
      @NotBlank String slug,
      @NotBlank String headline,
      @NotNull Instant publishedAt,
      boolean newsletterEligible) {}

  public record ArticleUnpublished(@NotNull UUID articleId) {}

  public record NewsletterDispatchRequested(
      @NotNull UUID articleId,
      @NotBlank String campaignKey,
      @NotBlank String slug,
      @NotBlank String headline) {}

  public record CommentSubmitted(
      @NotNull UUID commentId, @NotNull UUID articleId, @NotNull UUID authorId) {}

  public record SourceReference(
      @NotNull UUID sourcePostId,
      @NotBlank String account,
      @NotBlank String postId,
      @NotBlank String url,
      @NotNull Instant publishedAt) {}

  public record Claim(
      @NotBlank String text,
      @NotBlank String classification,
      @NotEmpty List<UUID> supportingSourceIds) {}
}
