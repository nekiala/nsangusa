package com.nsangusa.news.articles;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ArticleService {
  UUID createManual(ManualArticleCommand command, UUID editorId);

  ArticleView get(UUID articleId);

  ArticleView getPublishedBySlug(String slug);

  List<ArticleView> latestPublished(int limit);

  void approve(UUID articleId, UUID editorId);

  void edit(UUID articleId, long expectedVersion, ManualArticleCommand command, UUID editorId);

  void reject(UUID articleId, UUID editorId);

  void markScheduled(UUID articleId, UUID editorId);

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
      boolean commentsEnabled) {}

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
      double confidence)
      implements java.io.Serializable {}

  record SourceView(
      UUID sourcePostId, String account, String postId, String url, Instant publishedAt)
      implements java.io.Serializable {}
}
