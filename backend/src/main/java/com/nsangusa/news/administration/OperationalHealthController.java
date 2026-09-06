package com.nsangusa.news.administration;

import com.nsangusa.news.eventprocessing.EventOperations;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class OperationalHealthController {
  private final EventOperations eventOperations;

  OperationalHealthController(EventOperations eventOperations) {
    this.eventOperations = eventOperations;
  }

  @GetMapping("/summary")
  Map<String, Object> summary() {
    int poison = eventOperations.listFailed("poison", 200).size();
    int eligible = eventOperations.listFailed("eligible", 200).size();
    int replayFailed = eventOperations.listFailed("replay_failed", 200).size();
    return Map.of(
        "status",
        "available",
        "checkedAt",
        Instant.now(),
        "failedEvents",
        Map.of("eligible", eligible, "poison", poison, "replayFailed", replayFailed),
        "details",
        "Use Actuator and Kafka lag metrics for infrastructure diagnostics.");
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
      HttpServletRequest servletRequest) {
    UUID actorId = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    String auditMetadata =
        "remoteAddress="
            + servletRequest.getRemoteAddr()
            + ";userAgent="
            + safe(servletRequest.getHeader("User-Agent"));
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
    return request.dryRun() ? ResponseEntity.ok(result) : ResponseEntity.accepted().body(result);
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
