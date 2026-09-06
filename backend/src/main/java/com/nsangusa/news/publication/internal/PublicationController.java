package com.nsangusa.news.publication.internal;

import com.nsangusa.news.publication.PublicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/articles")
@PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
class PublicationController {
  private final PublicationService publication;

  PublicationController(PublicationService publication) {
    this.publication = publication;
  }

  @PostMapping("/{articleId}/schedule")
  ResponseEntity<ScheduleResponse> schedule(
      @PathVariable UUID articleId,
      @Valid @RequestBody ScheduleRequest request,
      Principal principal) {
    UUID editorId = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    return ResponseEntity.accepted()
        .body(new ScheduleResponse(publication.schedule(articleId, request.publishAt(), editorId)));
  }

  record ScheduleRequest(@Future Instant publishAt) {}

  record ScheduleResponse(UUID scheduleId) {}
}
