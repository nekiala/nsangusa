package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleContent;
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
  UUID storyCandidateId;

  @Column(nullable = false, unique = true)
  String slug;

  @Column(nullable = false)
  String headline;

  @Column(nullable = false, columnDefinition = "text")
  String summary;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  ArticleContent content;

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
  UUID pendingImageGenerationId;
  UUID approvedImageGenerationId;

  @Column(nullable = false)
  boolean imageApprovalRequired;

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
  String correctionNote;
  UUID approvedBy;
  Instant approvedAt;

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
    article.storyCandidateId = draft.storyCandidateId();
    article.slug = slugify(draft.slugSuggestion(), id);
    article.headline = draft.headline().trim();
    article.summary = draft.summary().trim();
    article.content = ArticleContent.fromBody(draft.body());
    article.body = article.content.plainText();
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
    article.imageApprovalRequired = true;
    article.confidence = draft.confidence();
    article.warnings =
        java.util.stream.Stream.concat(
                draft.uncertaintyNotes().stream(), draft.safetyFlags().stream())
            .distinct()
            .collect(java.util.stream.Collectors.joining("\n"));
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
    article.revisions.getLast().aiGenerationResult = draft;
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
    article.imageApprovalRequired = false;
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
        || state == ArticleState.SCHEDULED
        || state == ArticleState.ARCHIVED) {
      throw new IllegalStateException(
          "Published, unpublished, scheduled or archived articles cannot be edited directly");
    }
    apply(command);
    replaceSources(command.sources());
    state = ArticleState.AWAITING_REVIEW;
    approvedBy = null;
    approvedAt = null;
    addRevision("EDITOR_UPDATED", editorId);
  }

  void startCorrection(long expectedVersion, String note, UUID editorId) {
    if (version != expectedVersion) {
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "Article version does not match");
    }
    if (state != ArticleState.PUBLISHED && state != ArticleState.UNPUBLISHED) {
      throw new IllegalStateException(
          "Only published or unpublished articles can begin a correction");
    }
    if (note == null || note.isBlank() || note.length() > 2000) {
      throw new IllegalArgumentException(
          "A correction note of at most 2000 characters is required");
    }
    state = ArticleState.AWAITING_REVIEW;
    unpublishedAt = Instant.now();
    correctionNote = note.trim();
    approvedBy = null;
    approvedAt = null;
    humanReviewRequired = true;
    addRevision("CORRECTION_STARTED", editorId);
  }

  void imageCandidateReady(UUID generationId) {
    if (state == ArticleState.ARCHIVED || state == ArticleState.REJECTED) {
      throw new IllegalStateException("Rejected or archived articles cannot regenerate images");
    }
    pendingImageGenerationId = generationId;
    if (approvedImageGenerationId == null) {
      imageApprovalRequired = true;
    }
    touch();
  }

  boolean imageApproved(UUID generationId, String objectKey, String altText) {
    return imageApproved(generationId, objectKey, altText, true);
  }

  boolean imageApproved(UUID generationId, String objectKey, String altText, boolean generated) {
    if (state == ArticleState.ARCHIVED || state == ArticleState.REJECTED) {
      throw new IllegalStateException("Rejected or archived articles cannot select images");
    }
    boolean readyForReview = state == ArticleState.DRAFTING;
    approvedImageGenerationId = generationId;
    if (generationId.equals(pendingImageGenerationId)) {
      pendingImageGenerationId = null;
    }
    this.heroObjectKey = objectKey;
    this.imageAltText = altText;
    this.generatedImage = generated;
    this.imageApprovalRequired = false;
    if (readyForReview) {
      this.state = ArticleState.AWAITING_REVIEW;
    }
    addRevision("IMAGE_APPROVED", null);
    return readyForReview;
  }

  void approve(UUID editorId) {
    require(ArticleState.AWAITING_REVIEW);
    if (imageApprovalRequired) {
      throw new IllegalStateException("Generated image requires editorial approval");
    }
    this.state = ArticleState.APPROVED;
    approvedBy = editorId;
    approvedAt = Instant.now();
    addRevision("APPROVED", editorId);
  }

  void reject(UUID editorId) {
    require(ArticleState.AWAITING_REVIEW);
    state = ArticleState.REJECTED;
    addRevision("REJECTED", editorId);
  }

  void schedule(UUID editorId) {
    require(ArticleState.APPROVED);
    requireImageApproval();
    state = ArticleState.SCHEDULED;
    addRevision("SCHEDULED", editorId);
  }

  void cancelSchedule(UUID editorId) {
    require(ArticleState.SCHEDULED);
    state = ArticleState.APPROVED;
    addRevision("SCHEDULE_CANCELLED", editorId);
  }

  void publish(UUID actorId) {
    if (state != ArticleState.APPROVED && state != ArticleState.SCHEDULED) {
      throw new IllegalStateException("Article must be approved or scheduled before publication");
    }
    requireImageApproval();
    this.state = ArticleState.PUBLISHED;
    if (this.publishedAt == null) {
      this.publishedAt = Instant.now();
    }
    this.unpublishedAt = null;
    addRevision("PUBLISHED", actorId);
  }

  void unpublish(UUID actorId) {
    require(ArticleState.PUBLISHED);
    this.state = ArticleState.UNPUBLISHED;
    this.unpublishedAt = Instant.now();
    this.commentsEnabled = false;
    addRevision("UNPUBLISHED", actorId);
  }

  void restore(UUID actorId) {
    require(ArticleState.UNPUBLISHED);
    requireImageApproval();
    this.state = ArticleState.PUBLISHED;
    this.unpublishedAt = null;
    addRevision("RESTORED", actorId);
  }

  void archive(UUID actorId) {
    if (state != ArticleState.UNPUBLISHED && state != ArticleState.REJECTED) {
      throw new IllegalStateException("Only unpublished or rejected articles can be archived");
    }
    state = ArticleState.ARCHIVED;
    commentsEnabled = false;
    addRevision("ARCHIVED", actorId);
  }

  private void addRevision(String reason, UUID actorId) {
    touch();
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
            updatedAt));
    revisions.getLast().snapshot = snapshot();
  }

  private com.nsangusa.news.articles.ArticleService.RevisionSnapshot snapshot() {
    return new com.nsangusa.news.articles.ArticleService.RevisionSnapshot(
        slug,
        headline,
        summary,
        body,
        editorialContext,
        seoTitle,
        seoDescription,
        topic,
        java.util.Arrays.stream(tags.split(","))
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
        sources.stream()
            .map(
                source ->
                    new com.nsangusa.news.articles.ArticleService.SourceView(
                        source.sourcePostId,
                        source.account,
                        source.postId,
                        source.url,
                        source.publishedAt))
            .toList(),
        heroObjectKey,
        imageAltText,
        generatedImage,
        approvedImageGenerationId,
        pendingImageGenerationId,
        imageApprovalRequired,
        commentsEnabled,
        state,
        humanReviewRequired,
        confidence.doubleValue(),
        warnings.lines().toList(),
        correctionNote,
        approvedBy,
        approvedAt,
        publishedAt,
        updatedAt,
        effectiveContent());
  }

  ArticleContent effectiveContent() {
    return content == null ? ArticleContent.fromBody(body) : content;
  }

  private void require(ArticleState expected) {
    if (state != expected) {
      throw new IllegalStateException("Expected article state " + expected + " but was " + state);
    }
  }

  private void requireImageApproval() {
    if (imageApprovalRequired) {
      throw new IllegalStateException("Generated image requires editorial approval");
    }
  }

  private void touch() {
    updatedAt = Instant.now();
  }

  private void apply(com.nsangusa.news.articles.ArticleService.ManualArticleCommand command) {
    ArticleContent selectedContent =
        command.content() == null ? ArticleContent.fromBody(command.body()) : command.content();
    for (String value :
        java.util.Arrays.asList(
            command.headline(), command.summary(), command.seoTitle(), command.seoDescription())) {
      if (value == null
          || value.isBlank()
          || value.contains("<script")
          || value.contains("javascript:")) {
        throw new IllegalArgumentException("Article content is blank or unsafe");
      }
    }
    headline = command.headline().trim();
    summary = command.summary().trim();
    content = selectedContent;
    body = selectedContent.plainText();
    editorialContext =
        command.editorialContext() == null ? null : command.editorialContext().trim();
    seoTitle = command.seoTitle().trim();
    seoDescription = command.seoDescription().trim();
    topic = command.topic().trim();
    tags = String.join(",", command.tags());
    commentsEnabled = command.commentsEnabled();
  }

  private void replaceSources(List<com.nsangusa.news.articles.ArticleService.SourceView> selected) {
    if (selected == null || selected.isEmpty()) {
      throw new IllegalArgumentException("An article requires at least one source");
    }
    var ids =
        selected.stream()
            .map(com.nsangusa.news.articles.ArticleService.SourceView::sourcePostId)
            .collect(java.util.stream.Collectors.toSet());
    if (ids.size() != selected.size()) {
      throw new IllegalArgumentException("Article sources must be unique");
    }
    sources.removeIf(source -> !ids.contains(source.sourcePostId));
    for (var source : selected) {
      var existing =
          sources.stream()
              .filter(item -> item.sourcePostId.equals(source.sourcePostId()))
              .findFirst();
      if (existing.isPresent()) {
        var item = existing.orElseThrow();
        item.account = source.account();
        item.postId = source.postId();
        item.url = source.url();
        item.publishedAt = source.publishedAt();
      } else {
        sources.add(
            new ArticleSource(
                UUID.randomUUID(),
                this,
                source.sourcePostId(),
                source.account(),
                source.postId(),
                source.url(),
                source.publishedAt()));
      }
    }
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
