package com.nsangusa.news.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
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
      @NotNull Instant publishedAt,
      String conversationId) {
    public XPostDiscovered(
        UUID monitoredAccountId,
        String postId,
        String accountId,
        String handle,
        String canonicalUrl,
        String permittedText,
        Instant publishedAt) {
      this(
          monitoredAccountId,
          postId,
          accountId,
          handle,
          canonicalUrl,
          permittedText,
          publishedAt,
          null);
    }
  }

  public record XPostNormalized(
      @NotNull UUID sourcePostId,
      @NotBlank String postId,
      @NotBlank String handle,
      @NotBlank String canonicalUrl,
      @NotBlank String normalizedText,
      @NotNull Instant publishedAt,
      @NotEmpty Set<String> topics,
      String conversationId) {
    public XPostNormalized(
        UUID sourcePostId,
        String postId,
        String handle,
        String canonicalUrl,
        String normalizedText,
        Instant publishedAt,
        Set<String> topics) {
      this(sourcePostId, postId, handle, canonicalUrl, normalizedText, publishedAt, topics, null);
    }
  }

  public record StoryAnalysisRequested(
      @NotNull UUID storyCandidateId,
      @NotEmpty @Size(max = 20) List<@Valid SourceReference> sources,
      @NotBlank @Size(max = 60_000) String sourceMaterial) {}

  public record StoryAnalysisBlocked(
      @NotNull UUID storyCandidateId,
      @NotEmpty List<String> safetyFlags,
      @NotBlank String reason,
      @NotNull Instant blockedAt) {}

  public record ArticleDraftRequested(
      @NotNull UUID storyCandidateId,
      @NotEmpty @Size(max = 20) List<@Valid SourceReference> sources,
      @NotBlank @Size(max = 20_000) String analysis,
      @NotEmpty @Size(max = 50) List<@Valid Claim> claims,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 500) String> warnings) {}

  public record ArticleDraftGenerated(
      @NotNull UUID storyCandidateId,
      @NotBlank @Size(max = 300) String headline,
      @NotBlank @Size(max = 2000) String summary,
      @NotBlank @Size(max = 30_000) String body,
      @Size(max = 5000) String editorialContext,
      @NotBlank @Size(max = 300) String seoTitle,
      @NotBlank @Size(max = 500) String seoDescription,
      @NotBlank @Size(max = 250) String slugSuggestion,
      @NotEmpty @Size(max = 20) Set<@NotBlank @Size(max = 50) String> tags,
      @NotBlank @Size(max = 100) String topic,
      @NotEmpty @Size(max = 20) List<@Valid SourceReference> sources,
      @NotEmpty @Size(max = 50) List<@Valid Claim> claims,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 500) String> uncertaintyNotes,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 500) String> safetyFlags,
      boolean humanReviewRequired,
      @NotBlank @Size(max = 4000) String imagePrompt,
      @NotBlank @Size(max = 500) String imageAltText,
      @NotBlank @Size(max = 1000) String socialPreviewText,
      @NotBlank @Size(max = 100) String provider,
      @NotBlank @Size(max = 100) String model,
      @NotBlank @Size(max = 100) String promptVersion,
      @PositiveOrZero long inputTokens,
      @PositiveOrZero long outputTokens,
      @NotNull Instant generatedAt) {
    public ArticleDraftGenerated(
        UUID storyCandidateId,
        String headline,
        String summary,
        String body,
        String editorialContext,
        String seoTitle,
        String seoDescription,
        String slugSuggestion,
        Set<String> tags,
        String topic,
        List<SourceReference> sources,
        List<Claim> claims,
        BigDecimal confidence,
        List<String> uncertaintyNotes,
        List<String> safetyFlags,
        boolean humanReviewRequired,
        String imagePrompt,
        String imageAltText,
        String socialPreviewText,
        String provider,
        String model,
        String promptVersion,
        Instant generatedAt) {
      this(
          storyCandidateId,
          headline,
          summary,
          body,
          editorialContext,
          seoTitle,
          seoDescription,
          slugSuggestion,
          tags,
          topic,
          sources,
          claims,
          confidence,
          uncertaintyNotes,
          safetyFlags,
          humanReviewRequired,
          imagePrompt,
          imageAltText,
          socialPreviewText,
          provider,
          model,
          promptVersion,
          0,
          0,
          generatedAt);
    }
  }

  public record ArticleImageRequested(
      @NotNull UUID articleId, @NotBlank String prompt, @NotBlank String altText) {}

  public record ArticleImageGenerated(
      @NotNull UUID articleId,
      UUID generationId,
      @NotBlank String objectKey,
      @NotBlank String altText,
      @NotBlank String provider,
      @NotBlank String model,
      @NotNull Instant generatedAt) {}

  public record ArticleImageCandidateGenerated(
      @NotNull UUID articleId,
      @NotNull UUID generationId,
      @NotBlank String objectKey,
      @NotBlank String altText,
      @NotBlank String provider,
      @NotBlank String model,
      @NotNull Instant generatedAt) {}

  public record ArticleImageApproved(
      @NotNull UUID articleId,
      @NotNull UUID generationId,
      @NotBlank String objectKey,
      @NotBlank String altText,
      @NotNull UUID approvedBy,
      @NotNull Instant approvedAt,
      Boolean generatedImage) {
    public ArticleImageApproved(
        UUID articleId,
        UUID generationId,
        String objectKey,
        String altText,
        UUID approvedBy,
        Instant approvedAt) {
      this(articleId, generationId, objectKey, altText, approvedBy, approvedAt, true);
    }
  }

  public record ArticleReadyForReview(@NotNull UUID articleId) {}

  public record ArticleApproved(@NotNull UUID articleId, @NotNull UUID approvedBy) {}

  public record ArticlePublished(
      @NotNull UUID articleId,
      @NotBlank String slug,
      @NotBlank String headline,
      @NotNull Instant publishedAt,
      boolean newsletterEligible,
      UUID publishedBy,
      @Pattern(regexp = "human|system") String publisherType) {
    public ArticlePublished(
        UUID articleId,
        String slug,
        String headline,
        Instant publishedAt,
        boolean newsletterEligible) {
      this(articleId, slug, headline, publishedAt, newsletterEligible, null, null);
    }
  }

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
      @NotBlank @Size(max = 100) String account,
      @NotBlank @Size(max = 100) String postId,
      @NotBlank @Size(max = 2000) String url,
      @NotNull Instant publishedAt) {}

  public record Claim(
      @NotBlank @Size(max = 2000) String text,
      @NotBlank @Pattern(regexp = "REPORTED|UNVERIFIED|DISPUTED") String classification,
      @NotEmpty @Size(max = 20) List<@NotNull UUID> supportingSourceIds) {}
}
