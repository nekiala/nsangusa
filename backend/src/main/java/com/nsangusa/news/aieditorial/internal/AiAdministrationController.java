package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.nsangusa.news.aieditorial.AiAdministrationService;
import com.nsangusa.news.aieditorial.AiAdministrationService.ConfigurationView;
import com.nsangusa.news.aieditorial.AiAdministrationService.PromptView;
import com.nsangusa.news.aieditorial.AiAdministrationService.ProviderView;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ai-configuration")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class AiAdministrationController {
  private final AiAdministrationService administration;
  private final DurableCommandExecutor commands;

  AiAdministrationController(
      AiAdministrationService administration, DurableCommandExecutor commands) {
    this.administration = administration;
    this.commands = commands;
  }

  @GetMapping
  ConfigurationView current() {
    return administration.current();
  }

  @GetMapping("/providers")
  List<ProviderView> providers() {
    return administration.providers();
  }

  @GetMapping("/history")
  List<ConfigurationView> history(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return administration.history(page, size);
  }

  @PutMapping
  ConfigurationView select(
      @Valid @RequestBody SelectionRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    requireKey(key);
    UUID actor = actor(principal);
    String version =
        commands.execute(
            actor,
            key,
            "PUT /api/v1/admin/ai-configuration",
            request,
            () ->
                Long.toString(
                    administration.select(
                        request.expectedVersion(),
                        request.provider(),
                        request.model(),
                        request.promptVersion(),
                        request.reason(),
                        actor)));
    return administration.configuration(Long.parseLong(version));
  }

  @GetMapping("/prompts")
  List<PromptView> prompts(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return administration.prompts(page, size);
  }

  @GetMapping("/prompts/{version}")
  PromptView prompt(@PathVariable String version) {
    return administration.prompt(version);
  }

  @PostMapping("/prompts")
  @ResponseStatus(HttpStatus.CREATED)
  PromptView createPrompt(
      @Valid @RequestBody PromptRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    requireKey(key);
    UUID actor = actor(principal);
    String version =
        commands.execute(
            actor,
            key,
            "POST /api/v1/admin/ai-configuration/prompts",
            request,
            () ->
                administration.createPrompt(
                    request.expectedRevision(), request.guidance(), request.reason(), actor));
    return administration.prompt(version);
  }

  private static UUID actor(Principal principal) {
    return UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
  }

  private static void requireKey(String key) {
    if (!key.matches("[!-~]{8,200}")) {
      throw new IllegalArgumentException(
          "Idempotency-Key must contain 8–200 printable ASCII characters");
    }
  }

  record SelectionRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,100}") String provider,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,100}") String model,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,100}") String promptVersion,
      @NotBlank @Size(max = 500) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
      throw new IllegalArgumentException(
          "Only provider, model, promptVersion, expectedVersion and reason are accepted");
    }
  }

  record PromptRequest(
      @NotNull @PositiveOrZero Long expectedRevision,
      @NotBlank @Size(min = 10, max = 4000) String guidance,
      @NotBlank @Size(max = 500) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
      throw new IllegalArgumentException("Only expectedRevision, guidance and reason are accepted");
    }
  }
}
