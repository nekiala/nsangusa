package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.integration.SystemActors;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ArticleApplicationService implements ArticleService {
  private final ArticleRepository articles;
  private final DurableEventPublisher events;
  private final AuditService audit;
  private final SourceIngestionService sources;
  private final ArticleRevisionRepository revisions;
  private final org.springframework.context.ApplicationEventPublisher localEvents;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  ArticleApplicationService(
      ArticleRepository articles,
      DurableEventPublisher events,
      AuditService audit,
      SourceIngestionService sources,
      ArticleRevisionRepository revisions,
      org.springframework.context.ApplicationEventPublisher localEvents,
      com.fasterxml.jackson.databind.ObjectMapper mapper) {
    this.articles = articles;
    this.events = events;
    this.audit = audit;
    this.sources = sources;
    this.revisions = revisions;
    this.localEvents = localEvents;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public UUID createManual(ManualArticleCommand command, UUID editorId) {
    if (command.sources() == null || command.sources().isEmpty()) {
      throw new IllegalArgumentException("A manual article requires at least one source");
    }
    validateSourceReferences(command.sources());
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
  @Transactional
  public ArticleView getLocked(UUID articleId) {
    return view(
        articles
            .findLockedById(articleId)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Article not found")));
  }

  @Override
  @Transactional(readOnly = true)
  public ArticlePage list(ArticleState state, int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
    }
    var pageable =
        PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")));
    var result = state == null ? articles.findAll(pageable) : articles.findByState(state, pageable);
    return new ArticlePage(
        result.getContent().stream().map(this::view).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public ArticleView getPublishedBySlug(String slug) {
    return view(
        articles
            .findBySlugAndState(slug, ArticleState.PUBLISHED)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Published article not found")));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArticleView> latestPublished(int limit) {
    return articles
        .findByStateOrderByPublishedAtDesc(
            ArticleState.PUBLISHED,
            PageRequest.of(0, Math.min(Math.max(limit, 1), 100), Sort.by(Sort.Order.asc("id"))))
        .stream()
        .map(this::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public PublicArticlePage published(int page, int size) {
    if (page < 0 || page > 49_999 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be 0–49999 and size between 1 and 100");
    }
    var result = articles.findPublished(PageRequest.of(page, size));
    return new PublicArticlePage(
        result.getContent().stream().map(this::summary).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArticleSummary> related(String slug, int limit) {
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("Limit must be between 1 and 20");
    }
    getPublishedBySlug(slug);
    return articles.findRelated(slug, limit).stream().map(this::summary).toList();
  }

  private ArticleSummary summary(ArticleRepository.PublicEntry article) {
    return new ArticleSummary(
        article.getId(),
        article.getSlug(),
        article.getHeadline(),
        article.getSummary(),
        article.getTopic(),
        Arrays.stream(article.getTags().split(","))
            .map(String::trim)
            .filter(tag -> !tag.isEmpty())
            .distinct()
            .sorted()
            .toList(),
        article.getPublishedAt(),
        article.getUpdatedAt());
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
    validateSourceReferences(command.sources());
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
    var article = find(articleId);
    assertSourcesPublishable(article);
    article.schedule(editorId);
    articles.flush();
    audit.record(editorId, "ARTICLE_SCHEDULED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  public void cancelSchedule(UUID articleId, UUID editorId) {
    find(articleId).cancelSchedule(editorId);
    articles.flush();
    audit.record(editorId, "ARTICLE_SCHEDULE_CANCELLED", "article", articleId, java.util.Map.of());
  }

  @Override
  @Transactional
  public void startCorrection(UUID articleId, long expectedVersion, String note, UUID editorId) {
    var article = find(articleId);
    article.startCorrection(expectedVersion, note, editorId);
    audit.record(editorId, "ARTICLE_CORRECTION_STARTED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticleUnpublished",
        articleId,
        articleId,
        null,
        "article-correction-withdrawn:" + articleId + ":" + article.version,
        new ArticleUnpublished(articleId));
    visibilityChanged(articleId);
  }

  @Override
  @Transactional(readOnly = true)
  public RevisionPage revisions(UUID articleId, int page, int size) {
    find(articleId);
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
    }
    var result =
        revisions.findByArticleId(
            articleId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "revisionNumber")));
    return new RevisionPage(
        result.getContent().stream().map(this::revisionView).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public RevisionComparison compareRevisions(UUID articleId, int from, int to) {
    if (from < 1 || to < 1) {
      throw new IllegalArgumentException("Revision numbers must be positive");
    }
    var previous = revision(articleId, from);
    var current = revision(articleId, to);
    var before = mapper.valueToTree(previous.snapshot() == null ? previous : previous.snapshot());
    var after = mapper.valueToTree(current.snapshot() == null ? current : current.snapshot());
    var keys = new java.util.TreeSet<String>();
    before.properties().forEach(field -> keys.add(field.getKey()));
    after.properties().forEach(field -> keys.add(field.getKey()));
    var changed =
        keys.stream()
            .filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
            .toList();
    return new RevisionComparison(previous, current, changed);
  }

  private RevisionView revision(UUID articleId, int number) {
    return revisions
        .findByArticleIdAndRevisionNumber(articleId, number)
        .map(this::revisionView)
        .orElseThrow(
            () ->
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Article revision not found"));
  }

  private RevisionView revisionView(ArticleRevision revision) {
    return new RevisionView(
        revision.id,
        revision.revisionNumber,
        revision.reason,
        revision.actorId,
        revision.createdAt,
        revision.headline,
        revision.summary,
        revision.body,
        revision.snapshot,
        revision.aiGenerationResult,
        revision.snapshot == null);
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void publish(UUID articleId, UUID actorId, UUID correlationId, UUID causationId) {
    var article = find(articleId);
    assertSourcesPublishable(article);
    boolean firstPublication = article.publishedAt == null;
    article.publish(actorId);
    audit.record(actorId, "ARTICLE_PUBLISHED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticlePublished",
        articleId,
        correlationId,
        causationId,
        "article-published:" + articleId + ":" + article.version,
        new ArticlePublished(
            articleId,
            article.slug,
            article.headline,
            article.publishedAt,
            firstPublication,
            actorId,
            SystemActors.AUTOMATION.equals(actorId) ? "system" : "human"));
    visibilityChanged(articleId);
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
    visibilityChanged(articleId);
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void restore(UUID articleId, UUID actorId) {
    var article = find(articleId);
    assertSourcesPublishable(article);
    article.restore(actorId);
    audit.record(actorId, "ARTICLE_RESTORED", "article", articleId, java.util.Map.of());
    events.enqueue(
        "ArticlePublished",
        articleId,
        articleId,
        null,
        "article-restored:" + articleId + ":" + article.version,
        new ArticlePublished(
            articleId,
            article.slug,
            article.headline,
            article.publishedAt,
            false,
            actorId,
            "human"));
    visibilityChanged(articleId);
  }

  @Override
  @Transactional
  @CacheEvict(
      cacheNames = {"publishedArticleBySlug", "publishedArticleLists"},
      allEntries = true)
  public void archive(UUID articleId, UUID actorId) {
    find(articleId).archive(actorId);
    audit.record(actorId, "ARTICLE_ARCHIVED", "article", articleId, java.util.Map.of());
    visibilityChanged(articleId);
  }

  private void visibilityChanged(UUID articleId) {
    localEvents.publishEvent(new com.nsangusa.news.articles.ArticleVisibilityChanged(articleId));
  }

  private Article find(UUID id) {
    return articles
        .findById(id)
        .orElseThrow(
            () ->
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Article not found"));
  }

  private void validateSourceReferences(List<SourceView> references) {
    if (references == null
        || references.isEmpty()
        || references.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("An article requires valid source references");
    }
    var ids = references.stream().map(SourceView::sourcePostId).toList();
    if (ids.stream().anyMatch(java.util.Objects::isNull)
        || new java.util.HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException("Article source identifiers must be present and unique");
    }
    sources.assertSourcesPublishable(ids);
    for (var reference : references) {
      var source = sources.getSource(reference.sourcePostId());
      String account =
          reference.account() == null ? "" : reference.account().replaceFirst("^@", "");
      if (!source.handle().equalsIgnoreCase(account)
          || !source.postId().equals(reference.postId())
          || !source.canonicalUrl().equals(reference.url())
          || !source.publishedAt().equals(reference.publishedAt())) {
        throw new IllegalArgumentException(
            "Article source metadata does not match the source record");
      }
    }
  }

  private void assertSourcesPublishable(Article article) {
    sources.assertSourcesPublishable(
        article.sources.stream().map(source -> source.sourcePostId).toList());
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
        article.confidence.doubleValue(),
        article.storyCandidateId,
        article.seoTitle,
        article.seoDescription,
        article.approvedImageGenerationId,
        article.pendingImageGenerationId,
        article.imageApprovalRequired,
        article.correctionNote,
        article.approvedBy,
        article.approvedAt,
        article.effectiveContent());
  }
}
