package com.nsangusa.news.articles;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ArticleService {
  UUID createManual(ManualArticleCommand command, UUID editorId);

  ArticleView get(UUID articleId);

  ArticleView getLocked(UUID articleId);

  ArticleView getPublishedBySlug(String slug);

  List<ArticleView> latestPublished(int limit);

  PublicArticlePage published(int page, int size);

  List<ArticleSummary> related(String slug, int limit);

  ArticlePage list(ArticleState state, int page, int size);

  void approve(UUID articleId, UUID editorId);

  void edit(UUID articleId, long expectedVersion, ManualArticleCommand command, UUID editorId);

  void reject(UUID articleId, UUID editorId);

  void markScheduled(UUID articleId, UUID editorId);

  void cancelSchedule(UUID articleId, UUID editorId);

  void startCorrection(UUID articleId, long expectedVersion, String note, UUID editorId);

  RevisionPage revisions(UUID articleId, int page, int size);

  RevisionComparison compareRevisions(UUID articleId, int from, int to);

  void publish(UUID articleId, UUID actorId, UUID correlationId, UUID causationId);

  void unpublish(UUID articleId, UUID actorId);

  void restore(UUID articleId, UUID actorId);

  void archive(UUID articleId, UUID actorId);

  record ManualArticleCommand(
      String headline,
      String summary,
      String body,
      String editorialContext,
      String seoTitle,
      String seoDescription,
      String slugSuggestion,
      String topic,
      Set<String> tags,
      List<SourceView> sources,
      boolean commentsEnabled,
      ArticleContent content) {
    public ManualArticleCommand(
        String headline,
        String summary,
        String body,
        String editorialContext,
        String seoTitle,
        String seoDescription,
        String slugSuggestion,
        String topic,
        Set<String> tags,
        List<SourceView> sources,
        boolean commentsEnabled) {
      this(
          headline,
          summary,
          body,
          editorialContext,
          seoTitle,
          seoDescription,
          slugSuggestion,
          topic,
          tags,
          sources,
          commentsEnabled,
          null);
    }
  }

  record ArticleView(
      UUID id,
      String slug,
      String headline,
      String summary,
      String body,
      String editorialContext,
      String topic,
      Set<String> tags,
      ArticleState state,
      String heroObjectKey,
      String imageAltText,
      boolean generatedImage,
      boolean commentsEnabled,
      Instant publishedAt,
      Instant updatedAt,
      long version,
      List<SourceView> sources,
      List<String> warnings,
      double confidence,
      UUID storyCandidateId,
      String seoTitle,
      String seoDescription,
      UUID approvedImageGenerationId,
      UUID pendingImageGenerationId,
      boolean imageApprovalRequired,
      String correctionNote,
      UUID approvedBy,
      Instant approvedAt,
      ArticleContent content)
      implements java.io.Serializable {
    public ArticleView(
        UUID id,
        String slug,
        String headline,
        String summary,
        String body,
        String editorialContext,
        String topic,
        Set<String> tags,
        ArticleState state,
        String heroObjectKey,
        String imageAltText,
        boolean generatedImage,
        boolean commentsEnabled,
        Instant publishedAt,
        Instant updatedAt,
        long version,
        List<SourceView> sources,
        List<String> warnings,
        double confidence,
        UUID storyCandidateId,
        String seoTitle,
        String seoDescription,
        UUID approvedImageGenerationId,
        UUID pendingImageGenerationId,
        boolean imageApprovalRequired,
        String correctionNote,
        UUID approvedBy,
        Instant approvedAt) {
      this(
          id,
          slug,
          headline,
          summary,
          body,
          editorialContext,
          topic,
          tags,
          state,
          heroObjectKey,
          imageAltText,
          generatedImage,
          commentsEnabled,
          publishedAt,
          updatedAt,
          version,
          sources,
          warnings,
          confidence,
          storyCandidateId,
          seoTitle,
          seoDescription,
          approvedImageGenerationId,
          pendingImageGenerationId,
          imageApprovalRequired,
          correctionNote,
          approvedBy,
          approvedAt,
          null);
    }

    public ArticleView(
        UUID id,
        String slug,
        String headline,
        String summary,
        String body,
        String editorialContext,
        String topic,
        Set<String> tags,
        ArticleState state,
        String heroObjectKey,
        String imageAltText,
        boolean generatedImage,
        boolean commentsEnabled,
        Instant publishedAt,
        Instant updatedAt,
        long version,
        List<SourceView> sources,
        List<String> warnings,
        double confidence,
        UUID storyCandidateId,
        String seoTitle,
        String seoDescription,
        UUID approvedImageGenerationId,
        UUID pendingImageGenerationId,
        boolean imageApprovalRequired) {
      this(
          id,
          slug,
          headline,
          summary,
          body,
          editorialContext,
          topic,
          tags,
          state,
          heroObjectKey,
          imageAltText,
          generatedImage,
          commentsEnabled,
          publishedAt,
          updatedAt,
          version,
          sources,
          warnings,
          confidence,
          storyCandidateId,
          seoTitle,
          seoDescription,
          approvedImageGenerationId,
          pendingImageGenerationId,
          imageApprovalRequired,
          null,
          null,
          null);
    }

    public ArticleView(
        UUID id,
        String slug,
        String headline,
        String summary,
        String body,
        String editorialContext,
        String topic,
        Set<String> tags,
        ArticleState state,
        String heroObjectKey,
        String imageAltText,
        boolean generatedImage,
        boolean commentsEnabled,
        Instant publishedAt,
        Instant updatedAt,
        long version,
        List<SourceView> sources,
        List<String> warnings,
        double confidence) {
      this(
          id,
          slug,
          headline,
          summary,
          body,
          editorialContext,
          topic,
          tags,
          state,
          heroObjectKey,
          imageAltText,
          generatedImage,
          commentsEnabled,
          publishedAt,
          updatedAt,
          version,
          sources,
          warnings,
          confidence,
          null,
          headline,
          summary,
          null,
          null,
          false);
    }
  }

  record ArticlePage(List<ArticleView> items, int page, int size, long total) {}

  record ArticleSummary(
      UUID id,
      String slug,
      String headline,
      String summary,
      String topic,
      List<String> tags,
      Instant publishedAt,
      Instant updatedAt) {}

  record PublicArticlePage(List<ArticleSummary> items, int page, int size, long total) {}

  record RevisionSnapshot(
      String slug,
      String headline,
      String summary,
      String body,
      String editorialContext,
      String seoTitle,
      String seoDescription,
      String topic,
      Set<String> tags,
      List<SourceView> sources,
      String heroObjectKey,
      String imageAltText,
      boolean generatedImage,
      UUID approvedImageGenerationId,
      UUID pendingImageGenerationId,
      boolean imageApprovalRequired,
      boolean commentsEnabled,
      ArticleState state,
      boolean humanReviewRequired,
      double confidence,
      List<String> warnings,
      String correctionNote,
      UUID approvedBy,
      Instant approvedAt,
      Instant publishedAt,
      Instant updatedAt,
      ArticleContent content) {
    public RevisionSnapshot(
        String slug,
        String headline,
        String summary,
        String body,
        String editorialContext,
        String seoTitle,
        String seoDescription,
        String topic,
        Set<String> tags,
        List<SourceView> sources,
        String heroObjectKey,
        String imageAltText,
        boolean generatedImage,
        UUID approvedImageGenerationId,
        UUID pendingImageGenerationId,
        boolean imageApprovalRequired,
        boolean commentsEnabled,
        ArticleState state,
        boolean humanReviewRequired,
        double confidence,
        List<String> warnings,
        String correctionNote,
        UUID approvedBy,
        Instant approvedAt,
        Instant publishedAt,
        Instant updatedAt) {
      this(
          slug,
          headline,
          summary,
          body,
          editorialContext,
          seoTitle,
          seoDescription,
          topic,
          tags,
          sources,
          heroObjectKey,
          imageAltText,
          generatedImage,
          approvedImageGenerationId,
          pendingImageGenerationId,
          imageApprovalRequired,
          commentsEnabled,
          state,
          humanReviewRequired,
          confidence,
          warnings,
          correctionNote,
          approvedBy,
          approvedAt,
          publishedAt,
          updatedAt,
          null);
    }
  }

  record RevisionView(
      UUID id,
      int revisionNumber,
      String reason,
      UUID actorId,
      Instant createdAt,
      String headline,
      String summary,
      String body,
      RevisionSnapshot snapshot,
      com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated aiGenerationResult,
      boolean legacy) {}

  record RevisionPage(List<RevisionView> items, int page, int size, long total) {}

  record RevisionComparison(RevisionView from, RevisionView to, List<String> changedFields) {}

  record SourceView(
      UUID sourcePostId, String account, String postId, String url, Instant publishedAt)
      implements java.io.Serializable {}
}
