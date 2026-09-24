package com.nsangusa.news.administration;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.eventprocessing.EventOperations;
import com.nsangusa.news.eventprocessing.WorkflowHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class OperationalHealthController {
  private final EventOperations eventOperations;
  private final DurableCommandExecutor commands;
  private final WorkflowHealthService health;
  private final AuditService audit;

  OperationalHealthController(
      EventOperations eventOperations,
      DurableCommandExecutor commands,
      WorkflowHealthService health,
      AuditService audit) {
    this.eventOperations = eventOperations;
    this.commands = commands;
    this.health = health;
    this.audit = audit;
  }

  @GetMapping("/summary")
  Map<String, Object> summary() {
    var snapshot = health.snapshot();
    return Map.of(
        "status",
        "available",
        "checkedAt",
        snapshot.checkedAt(),
        "failedEvents",
        Map.of(
            "eligible",
            snapshot.failedEvents().getOrDefault("eligible", 0L),
            "poison",
            snapshot.failedEvents().getOrDefault("poison", 0L),
            "replayFailed",
            snapshot.failedEvents().getOrDefault("replay_failed", 0L)),
        "workflow",
        snapshot,
        "details",
        "Database-backed workflow counts, not a broker-connectivity or infrastructure-health assertion. Use Actuator and Kafka lag metrics for dependency diagnostics.");
  }

  @GetMapping("/failed-events/page")
  EventOperations.FailedEventPage failedPage(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return eventOperations.failedPage(status, page, size);
  }

  @GetMapping("/replays")
  EventOperations.ReplayPage replays(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return eventOperations.replays(page, size);
  }

  @GetMapping("/failed-events")
  List<EventOperations.FailedEventView> failedEvents(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    return eventOperations.listFailed(status, limit);
  }

  @PostMapping("/replays")
  ResponseEntity<EventOperations.ReplayRequestView> replay(
      @Valid @RequestBody ReplayRequest request,
      Principal principal,
      HttpServletRequest servletRequest,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actorId = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    String auditMetadata =
        "remoteAddress="
            + servletRequest.getRemoteAddr()
            + ";userAgent="
            + safe(servletRequest.getHeader("User-Agent"));
    UUID resultId =
        UUID.fromString(
            commands.execute(
                actorId,
                key,
                "event-replay-request",
                request,
                () -> {
                  var result =
                      eventOperations.requestReplay(
                          new EventOperations.ReplayCommand(
                              request.failedEventIds(),
                              request.failedFrom(),
                              request.failedTo(),
                              request.maximumMessages(),
                              request.messagesPerSecond(),
                              request.dryRun(),
                              request.includePoison(),
                              actorId,
                              request.reason(),
                              auditMetadata));
                  audit.record(
                      actorId,
                      request.dryRun() ? "EVENT_REPLAY_PREVIEWED" : "EVENT_REPLAY_REQUESTED",
                      "event_replay",
                      result.id(),
                      Map.of("candidateCount", Integer.toString(result.candidateCount())));
                  return result.id().toString();
                }));
    var result = eventOperations.getReplay(resultId);
    return request.dryRun() ? ResponseEntity.ok(result) : ResponseEntity.accepted().body(result);
  }

  @PostMapping("/replays/{id}/confirm")
  ResponseEntity<EventOperations.ReplayRequestView> confirm(
      @PathVariable UUID id,
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    UUID result =
        UUID.fromString(
            commands.execute(
                actor,
                key,
                "event-replay-confirm:" + id,
                id,
                () -> {
                  var confirmed = eventOperations.confirmReplay(id, actor);
                  audit.record(
                      actor,
                      "EVENT_REPLAY_CONFIRMED",
                      "event_replay",
                      confirmed.id(),
                      Map.of("previewId", id.toString()));
                  return confirmed.id().toString();
                }));
    return ResponseEntity.accepted().body(eventOperations.getReplay(result));
  }

  @GetMapping("/replays/{id}")
  EventOperations.ReplayRequestView replay(@PathVariable UUID id) {
    return eventOperations.getReplay(id);
  }

  @GetMapping("/replays/{id}/records")
  List<EventOperations.ReplayRecordView> replayRecords(@PathVariable UUID id) {
    return eventOperations.listReplayRecords(id);
  }

  private static String safe(String value) {
    if (value == null) {
      return "";
    }
    String sanitized = value.replaceAll("[\\r\\n]", "");
    return sanitized.substring(0, Math.min(sanitized.length(), 500));
  }

  record ReplayRequest(
      @Size(max = 100) Set<UUID> failedEventIds,
      Instant failedFrom,
      Instant failedTo,
      @Min(1) @Max(100) int maximumMessages,
      @Min(1) @Max(20) int messagesPerSecond,
      boolean dryRun,
      boolean includePoison,
      @NotBlank @Size(max = 500) String reason) {}
}
