package com.nsangusa.news.comments.internal;

import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class CommentController {
  private final CommentService comments;
  private final IdentityService identity;

  CommentController(CommentService comments, IdentityService identity) {
    this.comments = comments;
    this.identity = identity;
  }

  @GetMapping("/articles/{articleId}/comments")
  List<CommentService.CommentView> comments(@PathVariable UUID articleId) {
    return comments.approvedForArticle(articleId);
  }

  @PostMapping("/articles/{articleId}/comments")
  ResponseEntity<IdResponse> submit(
      @PathVariable UUID articleId,
      @Valid @RequestBody CommentRequest request,
      Principal principal) {
    UUID id = comments.submit(articleId, actorId(principal), request.body(), request.parentId());
    return ResponseEntity.accepted().body(new IdResponse(id));
  }

  @PutMapping("/comments/{commentId}")
  ResponseEntity<Void> edit(
      @PathVariable UUID commentId, @Valid @RequestBody EditRequest request, Principal principal) {
    comments.edit(commentId, actorId(principal), request.body());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/comments/{commentId}")
  ResponseEntity<Void> delete(@PathVariable UUID commentId, Principal principal) {
    comments.deleteByAuthor(commentId, actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/comments/{commentId}/reports")
  ResponseEntity<IdResponse> report(
      @PathVariable UUID commentId,
      @Valid @RequestBody ReportRequest request,
      Principal principal) {
    UUID id = comments.report(commentId, actorId(principal), request.reason(), request.details());
    return ResponseEntity.accepted().body(new IdResponse(id));
  }

  @PostMapping("/admin/comments/{commentId}/moderate")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> moderate(
      @PathVariable UUID commentId,
      @Valid @RequestBody ModerationRequest request,
      Principal principal) {
    comments.moderate(commentId, request.decision(), request.reason(), actorId(principal));
    return ResponseEntity.noContent().build();
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
  CommentService.PrivilegeView privilege(@PathVariable UUID userId) {
    return comments.privilege(userId);
  }

  @GetMapping("/admin/commenting-privileges/{userId}/history")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  List<CommentService.PrivilegeHistoryView> privilegeHistory(@PathVariable UUID userId) {
    return comments.privilegeHistory(userId);
  }

  @PostMapping("/admin/commenting-privileges/{userId}/suspend")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> suspend(
      @PathVariable UUID userId,
      @Valid @RequestBody SuspensionRequest request,
      Principal principal) {
    comments.suspend(userId, request.until(), request.reason(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/commenting-privileges/{userId}/restore")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> restore(
      @PathVariable UUID userId, @Valid @RequestBody ReasonRequest request, Principal principal) {
    comments.restorePrivilege(userId, request.reason(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  CommentService.GlobalSettingsView globalSettings() {
    return comments.globalSettings();
  }

  @PutMapping("/admin/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','ADMINISTRATOR')")
  ResponseEntity<Void> updateGlobalSettings(
      @Valid @RequestBody GlobalSettingsRequest request, Principal principal) {
    comments.updateGlobalSettings(request.toCommand(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/articles/{articleId}/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','EDITOR','ADMINISTRATOR')")
  CommentService.ArticleSettingsView articleSettings(@PathVariable UUID articleId) {
    return comments.articleSettings(articleId);
  }

  @PutMapping("/admin/articles/{articleId}/comment-settings")
  @PreAuthorize("hasAnyRole('MODERATOR','EDITOR','ADMINISTRATOR')")
  ResponseEntity<Void> updateArticleSettings(
      @PathVariable UUID articleId,
      @Valid @RequestBody ArticleSettingsRequest request,
      Principal principal) {
    comments.updateArticleSettings(articleId, request.toCommand(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  private UUID actorId(Principal principal) {
    return identity.profile(principal.getName()).id();
  }

  record CommentRequest(@NotBlank @Size(max = 5_000) String body, UUID parentId) {}

  record EditRequest(@NotBlank @Size(max = 5_000) String body) {}

  record ReportRequest(
      @NotBlank @Pattern(regexp = "abuse|harassment|hate|misinformation|spam|other") String reason,
      @Size(max = 2_000) String details) {}

  record ModerationRequest(
      @NotBlank @Pattern(regexp = "approved|rejected|spam|deleted") String decision,
      @Size(max = 2_000) String reason) {}

  record SuspensionRequest(Instant until, @NotBlank @Size(max = 2_000) String reason) {}

  record ReasonRequest(@NotBlank @Size(max = 2_000) String reason) {}

  record GlobalSettingsRequest(
      boolean enabled,
      boolean requireApproval,
      @Min(0) @Max(10_080) int editingWindowMinutes,
      @DecimalMin("0.0") @DecimalMax("1.0") double reviewSpamThreshold,
      @DecimalMin("0.0") @DecimalMax("1.0") double rejectSpamThreshold,
      @Min(1) @Max(100) int reportEscalationThreshold) {
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

  record ArticleSettingsRequest(Boolean enabledOverride, Boolean requireApprovalOverride) {
    CommentService.ArticleSettingsCommand toCommand() {
      return new CommentService.ArticleSettingsCommand(enabledOverride, requireApprovalOverride);
    }
  }

  record IdResponse(UUID id) {}
}
