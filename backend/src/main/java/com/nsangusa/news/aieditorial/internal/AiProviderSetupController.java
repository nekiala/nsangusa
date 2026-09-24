package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ai-configuration/setup")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class AiProviderSetupController {
  private final AiProviderSetupService setup;
  private final DurableCommandExecutor commands;

  AiProviderSetupController(AiProviderSetupService setup, DurableCommandExecutor commands) {
    this.setup = setup;
    this.commands = commands;
  }

  @GetMapping
  AiProviderSetupService.Status status() {
    return setup.status();
  }

  @PutMapping
  AiProviderSetupService.Status save(
      @Valid @RequestBody DraftRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    execute(
        principal,
        key,
        "PUT /api/v1/admin/ai-configuration/setup",
        request,
        actor ->
            setup.save(
                request.expectedVersion(),
                new AiProviderSetupService.SettingsInput(
                    request.provider(),
                    request.model(),
                    request.promptVersion(),
                    request.timeoutSeconds(),
                    request.maxOutputTokens(),
                    request.dailyTokenBudget(),
                    request.reason()),
                actor));
    return setup.status();
  }

  @PutMapping("/credential")
  AiProviderSetupService.Status rotate(
      @Valid @RequestBody CredentialRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    // The durable executor receives only a one-way digest, never serializes a plaintext credential.
    var fingerprint =
        Map.of(
            "expectedVersion",
            request.expectedVersion(),
            "credentialDigest",
            digest(request.apiKey()),
            "reason",
            request.reason());
    execute(
        principal,
        key,
        "PUT /api/v1/admin/ai-configuration/setup/credential",
        fingerprint,
        actor ->
            setup.rotate(request.expectedVersion(), request.apiKey(), request.reason(), actor));
    return setup.status();
  }

  @DeleteMapping("/credential")
  AiProviderSetupService.Status remove(
      @Valid @RequestBody ChangeRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    execute(
        principal,
        key,
        "DELETE /api/v1/admin/ai-configuration/setup/credential",
        request,
        actor -> setup.remove(request.expectedVersion(), request.reason(), actor));
    return setup.status();
  }

  @PostMapping("/activate")
  AiProviderSetupService.Status activate(
      @Valid @RequestBody ActivationRequest request,
      Principal principal,
      @RequestHeader("Idempotency-Key") String key) {
    execute(
        principal,
        key,
        "POST /api/v1/admin/ai-configuration/setup/activate",
        request,
        actor ->
            setup.activate(
                request.expectedVersion(),
                request.expectedConfigurationVersion(),
                request.acknowledgeOutboundDataAndCost(),
                request.reason(),
                actor));
    return setup.status();
  }

  private void execute(
      Principal principal,
      String key,
      String operation,
      Object request,
      java.util.function.Consumer<UUID> mutation) {
    if (!key.matches("[!-~]{8,200}"))
      throw new IllegalArgumentException("A valid idempotency key is required");
    UUID actor = UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
    commands.execute(
        actor,
        key,
        operation,
        request,
        () -> {
          mutation.accept(actor);
          return "applied";
        });
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Credential fingerprint unavailable");
    }
  }

  interface StrictRequest {
    @JsonAnySetter
    default void rejectUnknown(String name, Object value) {
      throw new IllegalArgumentException("Unknown AI provider setup field");
    }
  }

  record DraftRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Pattern(regexp = "fake|openai") String provider,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,100}") String model,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,100}") String promptVersion,
      @NotNull @Min(1) @Max(120) Integer timeoutSeconds,
      @NotNull @Min(256) @Max(16384) Integer maxOutputTokens,
      @NotNull @Min(1) @Max(1000000000) Long dailyTokenBudget,
      @NotBlank @Size(max = 500) String reason)
      implements StrictRequest {}

  record CredentialRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 20, max = 512) String apiKey,
      @NotBlank @Size(max = 500) String reason)
      implements StrictRequest {
    @Override
    public String toString() {
      return "CredentialRequest[redacted]";
    }
  }

  record ChangeRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max = 500) String reason)
      implements StrictRequest {}

  record ActivationRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotNull @PositiveOrZero Long expectedConfigurationVersion,
      @NotNull Boolean acknowledgeOutboundDataAndCost,
      @NotBlank @Size(max = 500) String reason)
      implements StrictRequest {}
}
