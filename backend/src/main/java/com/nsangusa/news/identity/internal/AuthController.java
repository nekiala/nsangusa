package com.nsangusa.news.identity.internal;

import com.nsangusa.news.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private final IdentityService identity;

  AuthController(IdentityService identity) {
    this.identity = identity;
  }

  @GetMapping("/csrf")
  java.util.Map<String, String> csrf(CsrfToken token) {
    return java.util.Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/register")
  ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
    identity.register(request.email(), request.password(), request.displayName());
    return ResponseEntity.accepted().body(new RegistrationResponse("verification_required"));
  }

  @GetMapping("/me")
  IdentityService.UserProfile me(Principal principal) {
    return identity.profile(principal.getName());
  }

  @PatchMapping("/me")
  IdentityService.UserProfile updateMe(
      Principal principal, @Valid @RequestBody ProfileUpdateRequest request) {
    return identity.updateProfile(
        principal.getName(),
        new IdentityService.ProfileUpdate(
            request.displayName(), request.newsletterFrequency(), request.expectedVersion()));
  }

  @GetMapping("/me/export")
  ResponseEntity<IdentityService.AccountDataExport> export(Principal principal) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"account-data.json\"")
        .body(identity.exportData(principal.getName()));
  }

  @DeleteMapping("/me")
  ResponseEntity<Void> deleteMe(
      Principal principal,
      HttpServletRequest servletRequest,
      @Valid @RequestBody AccountDeletionRequest request) {
    if (!"DELETE".equals(request.confirmation())) {
      throw new IllegalArgumentException("Account deletion confirmation is invalid");
    }
    identity.deleteAccount(principal.getName(), request.expectedVersion());
    var session = servletRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/sessions")
  List<IdentityService.UserSession> sessions(Principal principal, HttpServletRequest request) {
    var session = request.getSession(false);
    return identity.sessions(principal.getName(), session == null ? null : session.getId());
  }

  @DeleteMapping("/sessions/{sessionId}")
  ResponseEntity<Void> revokeSession(
      Principal principal, HttpServletRequest request, @PathVariable String sessionId) {
    identity.revokeSession(principal.getName(), sessionId);
    var session = request.getSession(false);
    if (session != null && sessionId.equals(session.getId())) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/verify-email")
  ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
    identity.verifyEmail(request.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-reset/request")
  ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
    identity.requestPasswordReset(request.email());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/verification/request")
  ResponseEntity<Void> requestVerification(@Valid @RequestBody PasswordResetRequest request) {
    identity.requestVerification(request.email());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/password-reset/confirm")
  ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetConfirm request) {
    identity.resetPassword(request.token(), request.newPassword());
    return ResponseEntity.noContent().build();
  }

  record RegistrationRequest(
      @Email @NotBlank @Size(max = 320) String email,
      @NotBlank @Size(min = 12, max = 128) String password,
      @NotBlank @Size(max = 100) String displayName) {}

  record RegistrationResponse(String status) {}

  record TokenRequest(@NotBlank @Size(max = 200) String token) {}

  record PasswordResetRequest(@Email @NotBlank @Size(max = 320) String email) {}

  record PasswordResetConfirm(
      @NotBlank @Size(max = 200) String token,
      @NotBlank @Size(min = 12, max = 128) String newPassword) {}

  record ProfileUpdateRequest(
      @Size(min = 1, max = 100) String displayName,
      @Pattern(regexp = "instant|immediate|daily|weekly") String newsletterFrequency,
      @NotNull @Min(0) Long expectedVersion) {}

  record AccountDeletionRequest(
      @NotBlank @Size(max = 6) String confirmation, @NotNull @Min(0) Long expectedVersion) {}
}
