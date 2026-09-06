package com.nsangusa.news.media.internal;

import com.nsangusa.news.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
class MediaController {
  private final MediaService media;

  MediaController(MediaService media) {
    this.media = media;
  }

  @GetMapping("/articles/{articleId}/images")
  List<MediaService.GenerationView> generations(@PathVariable UUID articleId) {
    return media.generations(articleId);
  }

  @PostMapping("/articles/{articleId}/images/regenerate")
  ResponseEntity<EventResponse> regenerate(
      @PathVariable UUID articleId,
      @Valid @RequestBody RegenerationRequest request,
      Principal principal) {
    return ResponseEntity.accepted()
        .body(
            new EventResponse(
                media.requestRegeneration(
                    articleId, request.prompt(), request.altText(), actor(principal))));
  }

  @PostMapping("/image-generations/{generationId}/approve")
  ResponseEntity<Void> approve(@PathVariable UUID generationId, Principal principal) {
    media.approve(generationId, actor(principal));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/media-assets/{assetId}")
  ResponseEntity<Void> delete(@PathVariable UUID assetId, Principal principal) {
    media.deleteAsset(assetId, actor(principal));
    return ResponseEntity.noContent().build();
  }

  private UUID actor(Principal principal) {
    return UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
  }

  record RegenerationRequest(
      @NotBlank @Size(max = 4_000) String prompt, @NotBlank @Size(max = 500) String altText) {}

  record EventResponse(UUID eventId) {}
}
