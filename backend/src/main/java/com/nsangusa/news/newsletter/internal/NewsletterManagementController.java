package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.newsletter.NewsletterManagementService;
import com.nsangusa.news.newsletter.NewsletterManagementService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/newsletter")
class NewsletterManagementController {
  private final NewsletterManagementService management;

  NewsletterManagementController(NewsletterManagementService management) {
    this.management = management;
  }

  @PostMapping("/preferences/link")
  ResponseEntity<LinkRequested> requestLink(@Valid @RequestBody LinkRequest request) {
    management.requestPreferenceLink(request.email());
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(
            new LinkRequested(
                "If this address has a confirmed subscription, a preference link will be emailed."));
  }

  @GetMapping("/preferences")
  ResponseEntity<PreferenceView> preferences(
      @RequestParam UUID id, @RequestParam @NotBlank String token) {
    return privateResponse(management.preferences(id, token));
  }

  @PostMapping("/preferences")
  ResponseEntity<Void> preferences(
      @RequestParam UUID id,
      @RequestParam @NotBlank String token,
      @Valid @RequestBody PreferenceRequest request,
      @RequestHeader("Idempotency-Key") String key) {
    management.updatePreferences(id, token, request.frequency(), key);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping("/admin/subscriptions")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<SubscriptionView>> subscriptions(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String frequency,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.subscriptions(status, frequency, page, size));
  }

  @GetMapping("/admin/subscriptions/{id}/consent")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<ConsentView>> consent(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.consent(id, page, size));
  }

  @GetMapping("/admin/campaigns")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<CampaignView>> campaigns(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.campaigns(type, status, page, size));
  }

  @GetMapping("/admin/delivery-history")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<DeliveryView>> deliveries(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID subscriptionId,
      @RequestParam(required = false) String campaignKey,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.deliveries(status, subscriptionId, campaignKey, page, size));
  }

  @GetMapping("/admin/deliveries/{id}/attempts")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<AttemptView>> attempts(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.attempts(id, page, size));
  }

  @GetMapping("/admin/suppressions")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Page<SuppressionView>> suppressions(
      @RequestParam(required = false) String reason,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return privateResponse(management.suppressions(reason, page, size));
  }

  @PostMapping("/admin/deliveries/{id}/reconciliation")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  ResponseEntity<Void> reconcile(
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Principal principal) {
    UUID actor =
        UUID.nameUUIDFromBytes(
            principal.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    management.reconcile(
        id,
        actor,
        new Reconciliation(
            request.outcome(), request.evidenceReference(), request.providerMessageId()),
        key);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .body(body);
  }

  record LinkRequest(@Email @NotBlank @Size(max = 320) String email) {}

  record LinkRequested(String message) {}

  record PreferenceRequest(
      @NotBlank @Pattern(regexp = "immediate|daily|weekly|all") String frequency) {}

  record ReconciliationRequest(
      @NotBlank @Pattern(regexp = "provider_accepted|not_sent|abandoned") String outcome,
      @NotBlank @Size(max = 200) String evidenceReference,
      @Size(max = 200) String providerMessageId) {}
}
