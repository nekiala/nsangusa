package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.articles.ArticleService.ManualArticleCommand;
import com.nsangusa.news.articles.ArticleService.SourceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

  ArticleController(ArticleService articles) {
    this.articles = articles;
  }

  @GetMapping("/articles")
  ResponseEntity<List<ArticleView>> latest(@RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic())
        .body(articles.latestPublished(limit));
  }

  @GetMapping("/articles/{slug}")
  ResponseEntity<ArticleView> article(@PathVariable String slug) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(2)).cachePublic())
        .body(articles.getPublishedBySlug(slug));
  }

  @GetMapping("/admin/articles/{id}")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ArticleView adminArticle(@PathVariable UUID id) {
    return articles.get(id);
  }

  @PostMapping("/admin/articles")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<IdResponse> create(
      @Valid @RequestBody ArticleCommandRequest request, Principal principal) {
    UUID id = articles.createManual(request.toCommand(), actorId(principal));
    return ResponseEntity.created(java.net.URI.create("/api/v1/admin/articles/" + id))
        .body(new IdResponse(id));
  }

  @org.springframework.web.bind.annotation.PutMapping("/admin/articles/{id}")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> edit(
      @PathVariable UUID id,
      @Valid @RequestBody ArticleCommandRequest request,
      @RequestParam long expectedVersion,
      Principal principal) {
    articles.edit(id, expectedVersion, request.toCommand(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/approve")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> approve(@PathVariable UUID id, Principal principal) {
    articles.approve(id, actorId(principal));
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/admin/articles/{id}/publish")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> publish(@PathVariable UUID id, Principal principal) {
    UUID actor = actorId(principal);
    articles.publish(id, actor, id, null);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/reject")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> reject(@PathVariable UUID id, Principal principal) {
    articles.reject(id, actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/unpublish")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> unpublish(@PathVariable UUID id, Principal principal) {
    articles.unpublish(id, actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/restore")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> restore(@PathVariable UUID id, Principal principal) {
    articles.restore(id, actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/articles/{id}/archive")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> archive(@PathVariable UUID id, Principal principal) {
    articles.archive(id, actorId(principal));
    return ResponseEntity.noContent().build();
  }

  private static UUID actorId(Principal principal) {
    return UUID.nameUUIDFromBytes(
        principal.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  record ArticleCommandRequest(
      @NotBlank @Size(max = 300) String headline,
      @NotBlank @Size(max = 2_000) String summary,
      @NotBlank @Size(max = 100_000) String body,
      @Size(max = 20_000) String editorialContext,
      @NotBlank @Size(max = 300) String seoTitle,
      @NotBlank @Size(max = 500) String seoDescription,
      @NotBlank @Size(max = 250) String slugSuggestion,
      @NotBlank @Size(max = 100) String topic,
      @NotEmpty Set<@NotBlank String> tags,
      @NotEmpty List<SourceView> sources,
      boolean commentsEnabled) {
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
          commentsEnabled);
    }
  }

  record IdResponse(UUID id) {}
}
