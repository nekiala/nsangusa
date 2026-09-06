package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "articles")
class Article {
  @Id UUID id;

  @Column(nullable = false, unique = true)
  String slug;

  @Column(nullable = false)
  String headline;

  @Column(nullable = false, columnDefinition = "text")
  String summary;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @Column(columnDefinition = "text")
  String editorialContext;

  @Column(nullable = false)
  String seoTitle;

  @Column(nullable = false)
  String seoDescription;

  @Column(nullable = false)
  String topic;

  @Column(nullable = false)
  String tags;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  ArticleState state;

  @Column(nullable = false)
  boolean humanReviewRequired;

  @Column(nullable = false)
  boolean commentsEnabled;

  @Column(nullable = false)
  boolean generatedImage;

  String heroObjectKey;
  String imageAltText;

  @Column(nullable = false)
  BigDecimal confidence;

  @Column(nullable = false, columnDefinition = "text")
  String warnings;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  Instant updatedAt;

  Instant publishedAt;
  Instant unpublishedAt;

  @Version long version;

  @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("publishedAt asc")
  List<ArticleSource> sources = new ArrayList<>();

  @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("revisionNumber asc")
  List<ArticleRevision> revisions = new ArrayList<>();

  protected Article() {}

  static Article fromDraft(UUID id, ArticleDraftGenerated draft) {
    validateUntrustedDraft(draft);
    var article = new Article();
    article.id = id;
    article.slug = slugify(draft.slugSuggestion(), id);
    article.headline = draft.headline().trim();
    article.summary = draft.summary().trim();
    article.body = draft.body().trim();
    article.editorialContext =
        draft.editorialContext() == null ? null : draft.editorialContext().trim();
    article.seoTitle = draft.seoTitle().trim();
    article.seoDescription = draft.seoDescription().trim();
    article.topic = draft.topic().trim();
    article.tags = String.join(",", draft.tags());
    article.state = ArticleState.DRAFTING;
    article.humanReviewRequired = true;
    article.commentsEnabled = true;
    article.generatedImage = false;
    article.confidence = draft.confidence();
    article.warnings = String.join("\n", draft.uncertaintyNotes());
    article.createdAt = draft.generatedAt();
    article.updatedAt = draft.generatedAt();
    draft
        .sources()
        .forEach(
            source ->
                article.sources.add(
                    new ArticleSource(
                        UUID.randomUUID(),
                        article,
                        source.sourcePostId(),
                        source.account(),
                        source.postId(),
                        source.url(),
                        source.publishedAt())));
    article.addRevision("AI_GENERATED", null);
    return article;
  }

  static Article manual(
      UUID id,
      com.nsangusa.news.articles.ArticleService.ManualArticleCommand command,
      UUID editorId) {
    if (command.sources() == null || command.sources().isEmpty()) {
      throw new IllegalArgumentException("A manual article requires at least one source");
    }
    var article = new Article();
    article.id = id;
    article.apply(command);
    article.slug = slugify(command.slugSuggestion(), id);
    article.state = ArticleState.AWAITING_REVIEW;
    article.humanReviewRequired = true;
    article.generatedImage = false;
    article.confidence = BigDecimal.ONE;
    article.warnings = "";
    article.createdAt = Instant.now();
    article.updatedAt = article.createdAt;
    command
        .sources()
        .forEach(
            source ->
                article.sources.add(
                    new ArticleSource(
                        UUID.randomUUID(),
                        article,
                        source.sourcePostId(),
                        source.account(),
                        source.postId(),
                        source.url(),
                        source.publishedAt())));
    article.addRevision("MANUAL_CREATED", editorId);
    return article;
  }

  void edit(
      long expectedVersion,
      com.nsangusa.news.articles.ArticleService.ManualArticleCommand command,
      UUID editorId) {
    if (version != expectedVersion) {
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "Article version does not match");
    }
    if (state == ArticleState.PUBLISHED
        || state == ArticleState.UNPUBLISHED
        || state == ArticleState.ARCHIVED) {
      throw new IllegalStateException(
          "Published, unpublished, or archived articles cannot be edited directly");
    }
    apply(command);
    state = ArticleState.AWAITING_REVIEW;
    addRevision("EDITOR_UPDATED", editorId);
    touch();
  }

  void imageReady(String objectKey, String altText) {
    require(ArticleState.DRAFTING);
    this.heroObjectKey = objectKey;
    this.imageAltText = altText;
    this.generatedImage = true;
    this.state = ArticleState.AWAITING_REVIEW;
    touch();
  }

  void approve(UUID editorId) {
    require(ArticleState.AWAITING_REVIEW);
    this.state = ArticleState.APPROVED;
    addRevision("APPROVED", editorId);
    touch();
  }

  void reject(UUID editorId) {
    require(ArticleState.AWAITING_REVIEW);
    state = ArticleState.REJECTED;
    addRevision("REJECTED", editorId);
    touch();
  }

  void schedule(UUID editorId) {
    require(ArticleState.APPROVED);
    state = ArticleState.SCHEDULED;
    addRevision("SCHEDULED", editorId);
    touch();
  }

  void publish(UUID actorId) {
    if (state != ArticleState.APPROVED && state != ArticleState.SCHEDULED) {
      throw new IllegalStateException("Article must be approved or scheduled before publication");
    }
    this.state = ArticleState.PUBLISHED;
    this.publishedAt = Instant.now();
    this.unpublishedAt = null;
    addRevision("PUBLISHED", actorId);
    touch();
  }

  void unpublish(UUID actorId) {
    require(ArticleState.PUBLISHED);
    this.state = ArticleState.UNPUBLISHED;
    this.unpublishedAt = Instant.now();
    this.commentsEnabled = false;
    addRevision("UNPUBLISHED", actorId);
    touch();
  }

  void restore(UUID actorId) {
    require(ArticleState.UNPUBLISHED);
    this.state = ArticleState.PUBLISHED;
    this.unpublishedAt = null;
    addRevision("RESTORED", actorId);
    touch();
  }

  void archive(UUID actorId) {
    if (state != ArticleState.UNPUBLISHED && state != ArticleState.REJECTED) {
      throw new IllegalStateException("Only unpublished or rejected articles can be archived");
    }
    state = ArticleState.ARCHIVED;
    commentsEnabled = false;
    addRevision("ARCHIVED", actorId);
    touch();
  }

  private void addRevision(String reason, UUID actorId) {
    revisions.add(
        new ArticleRevision(
            UUID.randomUUID(),
            this,
            revisions.size() + 1,
            headline,
            summary,
            body,
            reason,
            actorId,
            Instant.now()));
  }

  private void require(ArticleState expected) {
    if (state != expected) {
      throw new IllegalStateException("Expected article state " + expected + " but was " + state);
    }
  }

  private void touch() {
    updatedAt = Instant.now();
  }

  private void apply(com.nsangusa.news.articles.ArticleService.ManualArticleCommand command) {
    for (String value :
        List.of(
            command.headline(),
            command.summary(),
            command.body(),
            command.seoTitle(),
            command.seoDescription())) {
      if (value == null
          || value.isBlank()
          || value.contains("<script")
          || value.contains("javascript:")) {
        throw new IllegalArgumentException("Article content is blank or unsafe");
      }
    }
    headline = command.headline().trim();
    summary = command.summary().trim();
    body = command.body().trim();
    editorialContext =
        command.editorialContext() == null ? null : command.editorialContext().trim();
    seoTitle = command.seoTitle().trim();
    seoDescription = command.seoDescription().trim();
    topic = command.topic().trim();
    tags = String.join(",", command.tags());
    commentsEnabled = command.commentsEnabled();
  }

  private static void validateUntrustedDraft(ArticleDraftGenerated draft) {
    if (draft.sources().isEmpty()) {
      throw new IllegalArgumentException("A publishable draft requires at least one source");
    }
    for (String value :
        List.of(
            draft.headline(),
            draft.summary(),
            draft.body(),
            draft.seoTitle(),
            draft.seoDescription())) {
      if (value.contains("<script") || value.contains("javascript:")) {
        throw new IllegalArgumentException("Unsafe AI output rejected");
      }
    }
    if (!draft.humanReviewRequired()) {
      throw new IllegalArgumentException("AI drafts must require human review by default");
    }
  }

  private static String slugify(String suggestion, UUID id) {
    String slug =
        suggestion
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    return (slug.isBlank() ? "article" : slug) + "-" + id.toString().substring(0, 8);
  }
}
