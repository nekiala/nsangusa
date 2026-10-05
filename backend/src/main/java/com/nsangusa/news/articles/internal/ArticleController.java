package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.articles.ArticleService.ManualArticleCommand;
import com.nsangusa.news.articles.ArticleService.SourceView;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ArticleController {
  private final ArticleService articles;
  private final MediaService media;
  private final com.nsangusa.news.eventprocessing.DurableCommandExecutor commands;

  ArticleController(
      ArticleService articles,
      MediaService media,
      com.nsangusa.news.eventprocessing.DurableCommandExecutor commands) {
    this.articles = articles;
    this.media = media;
    this.commands = commands;
  }

  @GetMapping("/articles")
  ResponseEntity<List<ArticleView>> latest(@RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.latestPublished(limit));
  }

  @GetMapping("/articles/{slug}")
  ResponseEntity<ArticleView> article(
      @PathVariable String slug, @RequestParam(required = false) String lang) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.getPublishedBySlug(slug, language(lang)));
  }

  @GetMapping("/articles/discovery")
  ResponseEntity<ArticleService.PublicArticlePage> published(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String lang) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.published(page, size, language(lang)));
  }

  @GetMapping("/articles/{slug}/related")
  ResponseEntity<List<ArticleService.ArticleSummary>> related(
      @PathVariable String slug,
      @RequestParam(defaultValue = "3") int limit,
      @RequestParam(required = false) String lang) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.related(slug, limit, language(lang)));
  }

  @GetMapping("/articles/{slug}/image")
  ResponseEntity<byte[]> image(
      @PathVariable String slug, @RequestParam(defaultValue = "hero") String variant) {
    var article = articles.getPublishedBySlug(slug);
    if (article.heroObjectKey() == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Article has no approved image");
    }
    var image = media.image(article.heroObjectKey(), variant);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(org.springframework.http.MediaType.parseMediaType(image.contentType()))
        .body(image.bytes());
  }

  @GetMapping("/admin/articles")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<ArticleService.ArticlePage> list(
      @RequestParam(required = false) ArticleState state,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.list(state, page, size));
  }

  @GetMapping("/admin/articles/{id}")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ArticleView adminArticle(@PathVariable UUID id) {
    return articles.get(id);
  }

  @PostMapping("/admin/articles")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<IdResponse> create(
      @Valid @RequestBody ArticleCommandRequest request,
      Principal principal,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    UUID id =
        UUID.fromString(
            commands.execute(
                actorId(principal),
                key,
                "article-create",
                request,
                () -> articles.createManual(request.toCommand(), actorId(principal)).toString()));
    return ResponseEntity.created(java.net.URI.create("/api/v1/admin/articles/" + id))
        .body(new IdResponse(id));
  }

  @org.springframework.web.bind.annotation.PutMapping("/admin/articles/{id}")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> edit(
      @PathVariable UUID id,
      @Valid @RequestBody ArticleCommandRequest request,
      @RequestParam long expectedVersion,
      Principal principal,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    commands.execute(
        actorId(principal),
        key,
        "article-edit:" + id,
        java.util.Map.of("expectedVersion", expectedVersion, "article", request),
        () -> {
          articles.edit(id, expectedVersion, request.toCommand(), actorId(principal));
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/approve")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> approve(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    mutate(
        id,
        "approve",
        key,
        expectedVersion,
        principal,
        () -> articles.approve(id, actorId(principal)));
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/admin/articles/{articleId}/images/fallback")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<EventResponse> fallbackImage(
      @PathVariable UUID articleId,
      @Valid @RequestBody FallbackImageRequest request,
      Principal principal,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key) {
    UUID actor = actorId(principal);
    String eventId =
        commands.execute(
            actor,
            key,
            "article-image-fallback:" + articleId,
            request,
            () -> {
              var article = articles.getLocked(articleId);
              if (article.version() != request.expectedVersion()) {
                throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Article version does not match");
              }
              if (!Set.of(
                      ArticleState.DRAFTING, ArticleState.AWAITING_REVIEW, ArticleState.APPROVED)
                  .contains(article.state())) {
                throw new IllegalStateException(
                    "Only an editable draft can request a fallback image");
              }
              return media
                  .requestFallback(articleId, request.altText(), request.reason(), actor)
                  .toString();
            });
    return ResponseEntity.accepted().body(new EventResponse(UUID.fromString(eventId)));
  }

  @PostMapping("/admin/articles/{id}/publish")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> publish(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    UUID actor = actorId(principal);
    mutate(
        id,
        "publish",
        key,
        expectedVersion,
        principal,
        () -> articles.publish(id, actor, id, null));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/reject")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> reject(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    mutate(
        id,
        "reject",
        key,
        expectedVersion,
        principal,
        () -> articles.reject(id, actorId(principal)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/unpublish")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> unpublish(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    mutate(
        id,
        "unpublish",
        key,
        expectedVersion,
        principal,
        () -> articles.unpublish(id, actorId(principal)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/restore")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> restore(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    mutate(
        id,
        "restore",
        key,
        expectedVersion,
        principal,
        () -> articles.restore(id, actorId(principal)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/archive")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> archive(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    mutate(
        id,
        "archive",
        key,
        expectedVersion,
        principal,
        () -> articles.archive(id, actorId(principal)));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/articles/{id}/revisions")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<ArticleService.RevisionPage> revisions(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.revisions(id, page, size));
  }

  @GetMapping("/admin/articles/{id}/revisions/compare")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<ArticleService.RevisionComparison> compare(
      @PathVariable UUID id, @RequestParam int from, @RequestParam int to) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(articles.compareRevisions(id, from, to));
  }

  @PostMapping("/admin/articles/{id}/corrections")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> correction(
      @PathVariable UUID id,
      @Valid @RequestBody CorrectionRequest request,
      Principal principal,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    commands.execute(
        actorId(principal),
        key,
        "article-correction:" + id,
        request,
        () -> {
          articles.startCorrection(
              id, request.expectedVersion(), request.note(), actorId(principal));
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  private void mutate(
      UUID id,
      String operation,
      String key,
      Long expectedVersion,
      Principal principal,
      Runnable action) {
    commands.execute(
        actorId(principal),
        key,
        "article-" + operation + ":" + id,
        java.util.Collections.singletonMap("expectedVersion", expectedVersion),
        () -> {
          if (expectedVersion != null && articles.getLocked(id).version() != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "Article version does not match");
          }
          action.run();
          return null;
        });
  }

  record CorrectionRequest(@Min(0) long expectedVersion, @NotBlank @Size(max = 2000) String note) {}

  record FallbackImageRequest(
      @NotBlank @Size(max = 500) String altText,
      @NotBlank @Size(max = 2000) String reason,
      @NotNull @Min(0) Long expectedVersion) {}

  record EventResponse(UUID eventId) {}

  /** Unsupported or absent language requests fall back to each article's own language. */
  private static String language(String requested) {
    return requested != null && requested.matches("fr|en") ? requested : null;
  }

  record TranslationRequest(
      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "fr|en") String language,
      @NotBlank @Size(max = 300) String headline,
      @NotBlank @Size(max = 2_000) String summary,
      @NotBlank @Size(max = 100_000) String body,
      @Size(max = 20_000) String editorialContext,
      @NotBlank @Size(max = 300) String seoTitle,
      @NotBlank @Size(max = 500) String seoDescription,
      @Size(max = 500) String imageAltText) {
    ArticleService.TranslationView toView() {
      return new ArticleService.TranslationView(
          language,
          headline,
          summary,
          body,
          editorialContext,
          seoTitle,
          seoDescription,
          imageAltText);
    }
  }

  private static UUID actorId(Principal principal) {
    return UUID.nameUUIDFromBytes(
        principal.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  record ArticleCommandRequest(
      @NotBlank @Size(max = 300) String headline,
      @NotBlank @Size(max = 2_000) String summary,
      @Size(max = 100_000) String body,
      @Size(max = 20_000) String editorialContext,
      @NotBlank @Size(max = 300) String seoTitle,
      @NotBlank @Size(max = 500) String seoDescription,
      @NotBlank @Size(max = 250) String slugSuggestion,
      @NotBlank @Size(max = 100) String topic,
      @NotEmpty Set<@NotBlank String> tags,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid SourceView> sources,
      boolean commentsEnabled,
      @Valid com.nsangusa.news.articles.ArticleContent content,
      @Size(max = 4) List<@NotNull @Valid TranslationRequest> translations) {
    ArticleCommandRequest(
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
        com.nsangusa.news.articles.ArticleContent content) {
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
          content,
          null);
    }

    ArticleCommandRequest(
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

    @jakarta.validation.constraints.AssertTrue(message = "Supply content or a nonblank body") @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isContentPresent() {
      return content != null || (body != null && !body.isBlank());
    }

    ManualArticleCommand toCommand() {
      return new ManualArticleCommand(
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
          content,
          translations == null
              ? null
              : translations.stream().map(TranslationRequest::toView).toList());
    }
  }

  record IdResponse(UUID id) {}
}
