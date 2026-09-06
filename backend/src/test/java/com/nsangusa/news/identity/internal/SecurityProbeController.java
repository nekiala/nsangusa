package com.nsangusa.news.identity.internal;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecurityProbeController {
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
}
