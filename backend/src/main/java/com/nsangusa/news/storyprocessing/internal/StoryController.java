package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.storyprocessing.StoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/story-candidates")
@PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
class StoryController {
  private final StoryService stories;
  private final com.nsangusa.news.eventprocessing.DurableCommandExecutor commands;

  StoryController(
      StoryService stories, com.nsangusa.news.eventprocessing.DurableCommandExecutor commands) {
    this.stories = stories;
    this.commands = commands;
  }

  @GetMapping
  ResponseEntity<StoryService.StoryPage> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stories.list(page, size));
  }

  @PostMapping("/{id}/regenerate")
  ResponseEntity<IdResponse> regenerate(
      @PathVariable UUID id,
      Principal principal,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String key) {
    var actor = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    UUID candidate =
        UUID.fromString(
            commands.execute(
                actor,
                key,
                "story-regenerate:" + id,
                id,
                () -> stories.regenerate(id, actor).toString()));
    return ResponseEntity.accepted().body(new IdResponse(candidate));
  }

  record IdResponse(UUID id) {}
}
