package com.nsangusa.news.publication.internal;

import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.publication.PublicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
class PublicationController {
  private final PublicationService publication;
  private final DurableCommandExecutor commands;

  PublicationController(PublicationService publication, DurableCommandExecutor commands) {
    this.publication = publication;
    this.commands = commands;
  }

  @PostMapping("/articles/{articleId}/schedule")
  ResponseEntity<ScheduleResponse> schedule(
      @PathVariable UUID articleId,
      @Valid @RequestBody ScheduleRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = actor(principal);
    UUID scheduleId =
        UUID.fromString(
            commands.execute(
                actor,
                key,
                "POST /api/v1/admin/articles/" + articleId + "/schedule",
                request,
                () -> publication.schedule(articleId, request.publishAt(), actor).toString()));
    return ResponseEntity.accepted().body(new ScheduleResponse(scheduleId));
  }

  @GetMapping("/publication-schedules")
  PublicationService.SchedulePage list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID articleId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return publication.list(status, articleId, page, size);
  }

  @PatchMapping("/publication-schedules/{id}")
  PublicationService.ScheduleView reschedule(
      @PathVariable UUID id,
      @Valid @RequestBody RescheduleRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = actor(principal);
    commands.execute(
        actor,
        key,
        "PATCH /api/v1/admin/publication-schedules/" + id,
        request,
        () -> {
          publication.reschedule(id, request.expectedVersion(), request.publishAt(), actor);
          return null;
        });
    return publication.get(id);
  }

  @PostMapping("/publication-schedules/{id}/cancel")
  PublicationService.ScheduleView cancel(
      @PathVariable UUID id,
      @Valid @RequestBody CancelRequest request,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = actor(principal);
    commands.execute(
        actor,
        key,
        "POST /api/v1/admin/publication-schedules/" + id + "/cancel",
        request,
        () -> {
          publication.cancel(id, request.expectedVersion(), actor);
          return null;
        });
    return publication.get(id);
  }

  @GetMapping("/publication-policy")
  PublicationService.PolicyView policy() {
    return publication.policy();
  }

  private static UUID actor(Principal principal) {
    return UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
  }

  // Future checks run inside the command so a successful request can replay after publishAt.
  record ScheduleRequest(@NotNull Instant publishAt) {}

  record RescheduleRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotNull Instant publishAt) {}

  record CancelRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  record ScheduleResponse(UUID scheduleId) {}
}
