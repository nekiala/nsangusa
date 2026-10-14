package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/admin/x-accounts")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class XAccountController {
  private final SourceIngestionService service;
  private final XSourceProvider provider;
  private final boolean simulationEnabled;

  XAccountController(
      SourceIngestionService service,
      XSourceProvider provider,
      @org.springframework.beans.factory.annotation.Value("${news.providers.mode:disabled}")
          String providerMode,
      @org.springframework.beans.factory.annotation.Value("${news.x.live-enabled:false}")
          boolean liveEnabled) {
    this.service = service;
    this.provider = provider;
    this.simulationEnabled = "fake".equals(providerMode) && !liveEnabled;
  }

  @GetMapping("/capabilities")
  Capabilities capabilities() {
    return new Capabilities(simulationEnabled);
  }

  @GetMapping("/resolve")
  XSourceProvider.AccountLookup resolve(
      @RequestParam @Pattern(regexp = "@?[A-Za-z0-9_]{1,15}") String handle) {
    try {
      return provider.lookupAccount(handle);
    } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "X account lookup is rate limited; retry after the provider reset");
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.NOT_FOUND, "X account was not found");
    }
  }

  @GetMapping
  List<SourceIngestionService.AccountView> list(
      @RequestParam(defaultValue = "false") boolean includeRemoved) {
    return service.listAccounts(includeRemoved);
  }

  @GetMapping("/{id}")
  SourceIngestionService.AccountView get(@PathVariable UUID id) {
    return service.getAccount(id);
  }

  @PostMapping
  ResponseEntity<IdResponse> add(@Valid @RequestBody AddAccountRequest request) {
    UUID id =
        service.addAccount(
            request.accountId(),
            request.handle(),
            request.displayName(),
            request.topics(),
            request.relevanceThreshold());
    return ResponseEntity.created(URI.create("/api/v1/admin/x-accounts/" + id))
        .body(new IdResponse(id));
  }

  @PutMapping("/{id}")
  ResponseEntity<Void> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateAccountRequest request,
      Principal principal) {
    service.updateAccount(
        id,
        request.displayName(),
        request.topics(),
        request.relevanceThreshold(),
        request.monitoringEnabled(),
        actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> remove(
      @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, Principal principal) {
    service.removeAccount(id, request.reason(), actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/monitoring")
  ResponseEntity<Void> monitoring(
      @PathVariable UUID id, @Valid @RequestBody MonitoringRequest request) {
    service.setMonitoring(id, request.enabled());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/simulate-post")
  ResponseEntity<IdResponse> simulate(
      @PathVariable UUID id, @Valid @RequestBody SimulatedPostRequest request) {
    if (!simulationEnabled) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.FORBIDDEN, "Post simulation is available only with fake providers");
    }
    UUID sourceId =
        service.discoverPost(
            id,
            request.postId(),
            request.canonicalUrl(),
            request.permittedText(),
            request.publishedAt());
    return ResponseEntity.accepted().body(new IdResponse(sourceId));
  }

  record AddAccountRequest(
      @NotBlank String accountId,
      @NotBlank @Pattern(regexp = "@?[A-Za-z0-9_]{1,15}") String handle,
      @NotBlank @Size(max = 100) String displayName,
      @NotEmpty Set<@NotBlank String> topics,
      @DecimalMin("0.0") @DecimalMax("1.0") double relevanceThreshold) {}

  record MonitoringRequest(boolean enabled) {}

  record UpdateAccountRequest(
      @NotBlank @Size(max = 100) String displayName,
      @NotEmpty Set<@NotBlank @Size(max = 100) String> topics,
      @DecimalMin("0.0") @DecimalMax("1.0") double relevanceThreshold,
      boolean monitoringEnabled) {}

  record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

  record SimulatedPostRequest(
      @NotBlank String postId,
      @NotBlank String canonicalUrl,
      @NotBlank @Size(max = 10_000) String permittedText,
      @NotNull Instant publishedAt) {}

  record IdResponse(UUID id) {}

  record Capabilities(boolean simulationEnabled) {}

  static UUID actorId(Principal principal) {
    return UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8));
  }
}

@RestController
@RequestMapping("/api/v1/admin/blocked-source-accounts")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class BlockedSourceAccountController {
  private final SourceIngestionService service;

  BlockedSourceAccountController(SourceIngestionService service) {
    this.service = service;
  }

  @GetMapping
  List<SourceIngestionService.BlockedAccountView> list() {
    return service.listBlockedAccounts();
  }

  @PutMapping("/{accountId}")
  ResponseEntity<Void> block(
      @PathVariable String accountId,
      @Valid @RequestBody BlockRequest request,
      Principal principal) {
    service.blockAccount(accountId, request.reason(), XAccountController.actorId(principal));
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  @DeleteMapping("/{accountId}")
  ResponseEntity<Void> unblock(@PathVariable String accountId, Principal principal) {
    service.unblockAccount(accountId, XAccountController.actorId(principal));
    return ResponseEntity.noContent().build();
  }

  record BlockRequest(@NotBlank @Size(max = 500) String reason) {}
}

@RestController
@RequestMapping("/api/v1/admin/source-posts")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class SourceComplianceController {
  private final SourceIngestionService service;

  SourceComplianceController(SourceIngestionService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  List<SourceIngestionService.SourceSummary> list(
      @RequestParam(required = false) String accountId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit) {
    return service.listSources(accountId, status, limit);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  SourceIngestionService.SourceView get(@PathVariable UUID id) {
    return service.getSource(id);
  }

  @PostMapping("/{id}/exclude")
  ResponseEntity<Void> exclude(
      @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, Principal principal) {
    service.excludeSource(id, request.reason(), XAccountController.actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/compliance-edit")
  ResponseEntity<Void> complianceEdit(
      @PathVariable UUID id,
      @Valid @RequestBody ComplianceEditRequest request,
      Principal principal) {
    service.applyComplianceEdit(
        id, request.permittedText(), request.reason(), XAccountController.actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/content")
  ResponseEntity<Void> complianceDelete(
      @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, Principal principal) {
    service.applyComplianceDeletion(id, request.reason(), XAccountController.actorId(principal));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/reconciliation")
  ResponseEntity<Void> reconcile(
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationRequest request,
      Principal principal) {
    service.reconcileCompliance(
        id, request.successful(), request.notes(), XAccountController.actorId(principal));
    return ResponseEntity.noContent().build();
  }

  record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

  record ComplianceEditRequest(
      @NotBlank @Size(max = 10_000) String permittedText,
      @NotBlank @Size(max = 500) String reason) {}

  record ReconciliationRequest(boolean successful, @NotBlank @Size(max = 2_000) String notes) {}
}
