package com.nsangusa.news.comments.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class CommentController {
  private final CommentService comments;
  private final IdentityService identity;
  private final DurableCommandExecutor commands;
  private final ArticleService articles;
  private static final Set<String> MODERATOR_ROLES = Set.of("MODERATOR", "ADMINISTRATOR");

  CommentController(
      CommentService comments,
      IdentityService identity,
      DurableCommandExecutor commands,
      ArticleService articles) {
    this.comments = comments;
    this.identity = identity;
    this.commands = commands;
    this.articles = articles;
  }

  @GetMapping("/articles/{articleId}/comments")
  List<CommentService.CommentView> comments(@PathVariable UUID articleId) {
    return comments.approvedForArticle(articleId);
  }

  @GetMapping("/articles/{articleId}/discussion")
  ResponseEntity<CommentService.DiscussionView> discussion(
      @PathVariable UUID articleId, Principal principal) {
    var profile = principal == null ? null : identity.profile(principal.getName());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            comments.discussion(
                articleId,
                profile == null ? null : profile.id(),
                profile != null && eligible(profile)));
  }

  @PostMapping("/articles/{articleId}/comments")
  @PreAuthorize("hasAnyRole('READER','MODERATOR','EDITOR','ADMINISTRATOR')")
  ResponseEntity<IdResponse> submit(
      @PathVariable UUID articleId,
      @Valid @RequestBody CommentRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = readerId(principal);
    UUID id =
        UUID.fromString(
            commands.execute(
                actor,
                key,
                "POST /api/v1/articles/" + articleId + "/comments",
                request,
                () ->
                    comments
                        .submit(articleId, actor, request.body(), request.parentId())
                        .toString()));
    return ResponseEntity.accepted().body(new IdResponse(id));
  }

  @PutMapping("/comments/{commentId}")
  @PreAuthorize("hasAnyRole('READER','MODERATOR','EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> edit(
      @PathVariable UUID commentId,
      @Valid @RequestBody EditRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = readerId(principal);
    execute(
        actor,
        key,
        "PUT /api/v1/comments/" + commentId,
        request,
        () -> comments.edit(commentId, actor, request.body(), request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/comments/{commentId}")
  @PreAuthorize("hasAnyRole('READER','MODERATOR','EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> delete(
      @PathVariable UUID commentId,
      @RequestParam long expectedVersion,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    if (expectedVersion < 0)
      throw new IllegalArgumentException("Expected version must be nonnegative");
    UUID actor = readerId(principal);
    execute(
        actor,
        key,
        "DELETE /api/v1/comments/" + commentId,
        expectedVersion,
        () -> comments.deleteByAuthor(commentId, actor, expectedVersion));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/comments/{commentId}/reports")
  @PreAuthorize("hasAnyRole('READER','MODERATOR','EDITOR','ADMINISTRATOR')")
  ResponseEntity<IdResponse> report(
      @PathVariable UUID commentId,
      @Valid @RequestBody ReportRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = readerId(principal);
    UUID id =
        UUID.fromString(
            commands.execute(
                actor,
                key,
                "POST /api/v1/comments/" + commentId + "/reports",
                request,
                () ->
                    comments
                        .report(commentId, actor, request.reason(), request.details())
                        .toString()));
    return ResponseEntity.accepted().body(new IdResponse(id));
  }

  @PostMapping("/admin/comments/{commentId}/moderate")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> moderate(
      @PathVariable UUID commentId,
      @Valid @RequestBody ModerationRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = identity.requireAnyRole(principal.getName(), MODERATOR_ROLES);
    execute(
        actor,
        key,
        "POST /api/v1/admin/comments/" + commentId + "/moderate",
        request,
        () ->
            comments.moderate(
                commentId, request.decision(), request.reason(), actor, request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/comments")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.ModerationPage moderationPage(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) UUID articleId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return comments.moderationPage(state, articleId, page, size);
  }

  @GetMapping("/admin/comments/{commentId}")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.ModerationQueueItem moderationDetail(@PathVariable UUID commentId) {
    return comments.moderationDetail(commentId);
  }

  @GetMapping("/admin/comment-articles")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ArticleOptions commentArticles(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    var result = articles.list(null, page, size);
    return new ArticleOptions(
        result.items().stream()
            .map(
                article ->
                    new ArticleOption(
                        article.id(),
                        article.headline(),
                        article.slug(),
                        article.state().name(),
                        article.commentsEnabled()))
            .toList(),
        result.page(),
        result.size(),
        result.total());
  }

  @GetMapping("/admin/comments/queue")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  List<CommentService.ModerationQueueItem> moderationQueue(
      @RequestParam(defaultValue = "50") int limit) {
    return comments.moderationQueue(limit);
  }

  @GetMapping("/admin/comment-reports")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  List<CommentService.ReportView> reportQueue(@RequestParam(defaultValue = "50") int limit) {
    return comments.reportQueue(limit);
  }

  @GetMapping("/admin/comments/{commentId}/history")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  List<CommentService.ModerationHistoryView> history(@PathVariable UUID commentId) {
    return comments.moderationHistory(commentId);
  }

  @GetMapping("/admin/commenting-privileges/{userId}")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.PrivilegeView privilege(@PathVariable UUID userId, Principal principal) {
    identity.communityUser(principal.getName(), userId);
    return comments.privilege(userId);
  }

  @GetMapping("/admin/commenting-privileges/{userId}/history")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  List<CommentService.PrivilegeHistoryView> privilegeHistory(
      @PathVariable UUID userId, Principal principal) {
    identity.communityUser(principal.getName(), userId);
    return comments.privilegeHistory(userId);
  }

  @PostMapping("/admin/commenting-privileges/{userId}/suspend")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> suspend(
      @PathVariable UUID userId,
      @Valid @RequestBody SuspensionRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = identity.requireAnyRole(principal.getName(), MODERATOR_ROLES);
    identity.communityUser(principal.getName(), userId);
    execute(
        actor,
        key,
        "POST /api/v1/admin/commenting-privileges/" + userId + "/suspend",
        request,
        () ->
            comments.suspend(
                userId, request.until(), request.reason(), actor, request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/commenting-privileges/{userId}/restore")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> restore(
      @PathVariable UUID userId,
      @Valid @RequestBody ReasonRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = identity.requireAnyRole(principal.getName(), MODERATOR_ROLES);
    identity.communityUser(principal.getName(), userId);
    execute(
        actor,
        key,
        "POST /api/v1/admin/commenting-privileges/" + userId + "/restore",
        request,
        () ->
            comments.restorePrivilege(userId, request.reason(), actor, request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.GlobalSettingsView globalSettings() {
    return comments.globalSettings();
  }

  @PutMapping("/admin/comment-settings")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Void> updateGlobalSettings(
      @Valid @RequestBody GlobalSettingsRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = identity.requireAnyRole(principal.getName(), Set.of("ADMINISTRATOR"));
    execute(
        actor,
        key,
        "PUT /api/v1/admin/comment-settings",
        request,
        () -> comments.updateGlobalSettings(request.toCommand(), actor, request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/articles/{articleId}/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.ArticleSettingsView articleSettings(@PathVariable UUID articleId) {
    return comments.articleSettings(articleId);
  }

  @PutMapping("/admin/articles/{articleId}/comment-settings")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Void> updateArticleSettings(
      @PathVariable UUID articleId,
      @Valid @RequestBody ArticleSettingsRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = identity.requireAnyRole(principal.getName(), Set.of("ADMINISTRATOR"));
    execute(
        actor,
        key,
        "PUT /api/v1/admin/articles/" + articleId + "/comment-settings",
        request,
        () ->
            comments.updateArticleSettings(
                articleId, request.toCommand(), actor, request.expectedVersion()));
    return ResponseEntity.noContent().build();
  }

  private UUID readerId(Principal principal) {
    var profile = identity.profile(principal.getName());
    if (!eligible(profile))
      throw new AccessDeniedException("This account is not eligible to comment");
    return profile.id();
  }

  private static boolean eligible(IdentityService.UserProfile profile) {
    return profile.emailVerified()
        && profile.roles().stream()
            .anyMatch(Set.of("READER", "MODERATOR", "EDITOR", "ADMINISTRATOR")::contains);
  }

  private void execute(UUID actor, String key, String operation, Object request, Runnable work) {
    commands.execute(
        actor,
        key,
        operation,
        request,
        () -> {
          work.run();
          return null;
        });
  }

  record CommentRequest(@NotBlank @Size(max = 5_000) String body, UUID parentId) {}

  record EditRequest(
      @NotBlank @Size(max = 5_000) String body, @NotNull @Min(0) Long expectedVersion) {}

  record ReportRequest(
      @NotBlank @Pattern(regexp = "abuse|harassment|hate|misinformation|spam|other") String reason,
      @Size(max = 2_000) String details) {}

  record ModerationRequest(
      @NotBlank @Pattern(regexp = "approved|rejected|spam|deleted") String decision,
      @NotBlank @Size(max = 2_000) String reason,
      @NotNull @Min(0) Long expectedVersion) {}

  record SuspensionRequest(
      Instant until,
      @NotBlank @Size(max = 2_000) String reason,
      @NotNull @Min(-1) Long expectedVersion) {}

  record ReasonRequest(
      @NotBlank @Size(max = 2_000) String reason, @NotNull @Min(-1) Long expectedVersion) {}

  record GlobalSettingsRequest(
      boolean enabled,
      boolean requireApproval,
      @Min(0) @Max(10_080) int editingWindowMinutes,
      @DecimalMin("0.0") @DecimalMax("1.0") double reviewSpamThreshold,
      @DecimalMin("0.0") @DecimalMax("1.0") double rejectSpamThreshold,
      @Min(1) @Max(100) int reportEscalationThreshold,
      @NotNull @Min(-1) Long expectedVersion) {
    CommentService.GlobalSettingsCommand toCommand() {
      return new CommentService.GlobalSettingsCommand(
          enabled,
          requireApproval,
          editingWindowMinutes,
          reviewSpamThreshold,
          rejectSpamThreshold,
          reportEscalationThreshold);
    }
  }

  record ArticleSettingsRequest(
      Boolean enabledOverride,
      Boolean requireApprovalOverride,
      @NotNull @Min(-1) Long expectedVersion) {
    CommentService.ArticleSettingsCommand toCommand() {
      return new CommentService.ArticleSettingsCommand(enabledOverride, requireApprovalOverride);
    }
  }

  record IdResponse(UUID id) {}

  record ArticleOption(
      UUID id, String headline, String slug, String state, boolean commentsEnabled) {}

  record ArticleOptions(List<ArticleOption> items, int page, int size, long total) {}

  @GetMapping("/admin/commenting-users")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<List<IdentityService.CommunityUser>> commentingUsers(
      @RequestParam String query,
      @RequestParam(defaultValue = "20") int limit,
      Principal principal) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(identity.findCommunityUsers(principal.getName(), query, limit));
  }

  @GetMapping("/admin/commenting-users/{userId}")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<IdentityService.CommunityUser> commentingUser(
      @PathVariable UUID userId, Principal principal) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(identity.communityUser(principal.getName(), userId));
  }
}
