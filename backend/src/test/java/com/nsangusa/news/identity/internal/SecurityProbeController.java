package com.nsangusa.news.identity.internal;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.test.context.TestComponent
class SecurityProbeController {
  @GetMapping({"/actuator/prometheus", "/actuator/metrics", "/actuator/info"})
  Map<String, String> management() {
    return Map.of("status", "management");
  }

  @GetMapping("/api/v1/articles/security-probe")
  Map<String, String> article() {
    return Map.of("status", "public");
  }

  @GetMapping("/api/v1/auth/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/api/v1/admin/security-probe")
  @PreAuthorize("hasAnyRole('EDITOR','ADMINISTRATOR')")
  org.springframework.http.ResponseEntity<Void> mutate() {
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @PostMapping({
    "/api/v1/newsletter/subscriptions",
    "/api/v1/newsletter/confirm",
    "/api/v1/newsletter/preferences",
    "/api/v1/newsletter/preferences/link"
  })
  org.springframework.http.ResponseEntity<Void> newsletterMutation() {
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/newsletter/unsubscribe")
  org.springframework.http.ResponseEntity<Void> signedUnsubscribe() {
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/newsletter/provider-webhooks/security-probe")
  org.springframework.http.ResponseEntity<Void> providerWebhook() {
    return org.springframework.http.ResponseEntity.noContent().build();
  }
}
