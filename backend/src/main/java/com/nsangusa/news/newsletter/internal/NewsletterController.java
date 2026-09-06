package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.newsletter.NewsletterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/newsletter")
class NewsletterController {
  private final NewsletterService newsletter;
  private final NewsletterWebhookService webhooks;

  NewsletterController(NewsletterService newsletter, NewsletterWebhookService webhooks) {
    this.newsletter = newsletter;
    this.webhooks = webhooks;
  }

  @PostMapping("/subscriptions")
  ResponseEntity<NewsletterService.SubscriptionRequested> subscribe(
      @Valid @RequestBody SubscriptionRequest request) {
    return ResponseEntity.accepted()
        .body(newsletter.subscribe(request.email(), request.consentSource(), request.frequency()));
  }

  @GetMapping("/confirm")
  ResponseEntity<Void> confirm(@RequestParam UUID id, @RequestParam @NotBlank String token) {
    newsletter.confirm(id, token);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/unsubscribe")
  ResponseEntity<Void> unsubscribe(@RequestParam UUID id, @RequestParam @NotBlank String token) {
    newsletter.unsubscribe(id, token);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/preferences")
  ResponseEntity<Void> preferences(
      @RequestParam UUID id,
      @RequestParam @NotBlank String token,
      @Valid @RequestBody PreferenceRequest request) {
    newsletter.updatePreferences(id, token, request.frequency());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/provider-webhooks/{provider}")
  ResponseEntity<Void> webhook(
      @PathVariable String provider,
      @RequestHeader("X-Webhook-Signature") String signature,
      @RequestBody String body) {
    webhooks.process(provider, signature, body);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/deliveries")
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  java.util.List<NewsletterService.DeliveryView> deliveries(
      @RequestParam(defaultValue = "100") int limit) {
    return newsletter.recentDeliveries(limit);
  }

  record SubscriptionRequest(
      @Email @NotBlank String email,
      @NotBlank String consentSource,
      @Pattern(regexp = "immediate|daily|weekly|all") String frequency) {}

  record PreferenceRequest(@Pattern(regexp = "immediate|daily|weekly|all") String frequency) {}
}
