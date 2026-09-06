package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.media.MediaService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ArticleApplicationService implements ArticleService {
  private final ArticleRepository articles;
  private final DurableEventPublisher events;
  private final AuditService audit;
  private final MediaService media;

  ArticleApplicationService(
      ArticleRepository articles,
      DurableEventPublisher events,
      AuditService audit,
      MediaService media) {
    this.articles = articles;
    this.events = events;
    this.audit = audit;
    this.media = media;
  }

  @Override
  @Transactional
  public UUID createManual(ManualArticleCommand command, UUID editorId) {
    UUID id = UUID.randomUUID();
    articles.save(Article.manual(id, command, editorId));
    audit.record(editorId, "ARTICLE_MANUAL_CREATED", "article", id, java.util.Map.of());
    return id;
  }

  @Override
  @Transactional(readOnly = true)
  public ArticleView get(UUID articleId) {
    return view(find(articleId));
  }

  @Override
  @Transactional(readOnly = true)
  @Cacheable(cacheNames = "publishedArticleBySlug", key = "#slug")
  public ArticleView getPublishedBySlug(String slug) {
    return view(
        articles
            .findBySlugAndState(slug, ArticleState.PUBLISHED)
            .orElseThrow(() -> new IllegalArgumentException("Published article not found")));
  }

  @Override
  @Transactional(readOnly = true)
  @Cacheable(cacheNames = "publishedArticleLists", key = "#limit")
  public List<ArticleView> latestPublished(int limit) {
    return articles
        .findByStateOrderByPublishedAtDesc(
            ArticleState.PUBLISHED, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
        .stream()
        .map(this::view)
        .toList();
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void approve(UUID articleId, UUID editorId) {
    var article = find(articleId);
    article.approve(editorId);
    audit.record(editorId, "ARTICLE_APPROVED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticleApproved",
        articleId,
        articleId,
        null,
        "article-approved:" + articleId + ":" + article.version,
        new ArticleApproved(articleId, editorId));
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void edit(
      UUID articleId, long expectedVersion, ManualArticleCommand command, UUID editorId) {
    find(articleId).edit(expectedVersion, command, editorId);
    audit.record(editorId, "ARTICLE_EDITED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  public void reject(UUID articleId, UUID editorId) {
    find(articleId).reject(editorId);
    audit.record(editorId, "ARTICLE_REJECTED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  public void markScheduled(UUID articleId, UUID editorId) {
    find(articleId).schedule(editorId);
    audit.record(editorId, "ARTICLE_SCHEDULED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void publish(UUID articleId, UUID actorId, UUID correlationId, UUID causationId) {
    if (!media.isApprovedOrAbsent(articleId)) {
      throw new IllegalStateException("Generated image requires editorial approval");
    }
    var article = find(articleId);
    article.publish(actorId);
    audit.record(actorId, "ARTICLE_PUBLISHED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticlePublished",
        articleId,
        correlationId,
        causationId,
        "article-published:" + articleId + ":" + article.version,
        new ArticlePublished(articleId, article.slug, article.headline, article.publishedAt, true));
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void unpublish(UUID articleId, UUID actorId) {
    var article = find(articleId);
    article.unpublish(actorId);
    audit.record(actorId, "ARTICLE_UNPUBLISHED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticleUnpublished",
        articleId,
        articleId,
        null,
        "article-unpublished:" + articleId + ":" + article.version,
        new ArticleUnpublished(articleId));
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void restore(UUID articleId, UUID actorId) {
    find(articleId).restore(actorId);
    audit.record(actorId, "ARTICLE_RESTORED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void archive(UUID articleId, UUID actorId) {
    find(articleId).archive(actorId);
    audit.record(actorId, "ARTICLE_ARCHIVED", "article", articleId, java.util.Map.of());
  }

  private Article find(UUID id) {
    return articles
        .findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Article not found"));
  }

  private ArticleView view(Article article) {
    Set<String> tags =
        Arrays.stream(article.tags.split(","))
            .filter(tag -> !tag.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    return new ArticleView(
        article.id,
        article.slug,
        article.headline,
        article.summary,
        article.body,
        article.editorialContext,
        article.topic,
        tags,
        article.state,
        article.heroObjectKey,
        article.imageAltText,
        article.generatedImage,
        article.commentsEnabled,
        article.publishedAt,
        article.updatedAt,
        article.version,
        article.sources.stream()
            .map(
                source ->
                    new SourceView(
                        source.sourcePostId,
                        source.account,
                        source.postId,
                        source.url,
                        source.publishedAt))
            .toList(),
        article.warnings.lines().toList(),
        article.confidence.doubleValue());
  }
}
