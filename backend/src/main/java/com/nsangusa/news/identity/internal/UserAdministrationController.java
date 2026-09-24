package com.nsangusa.news.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class UserAdministrationController {
  private final UserAdministrationService users;

  UserAdministrationController(UserAdministrationService users) {
    this.users = users;
  }

  @GetMapping
  ResponseEntity<UserAdministrationService.UserPage> list(
      Principal principal,
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(required = false) UserAccount.Role role,
      @RequestParam(required = false) UserAdministrationService.Status status,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(users.list(principal.getName(), q, role, status, page, size));
  }

  @GetMapping("/{id}")
  ResponseEntity<UserAdministrationService.UserView> get(
      Principal principal, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(users.get(principal.getName(), id));
  }

  @PutMapping("/{id}/roles")
  ResponseEntity<Void> roles(
      Principal principal,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
      @Valid @RequestBody RoleChangeRequest request) {
    users.changeRoles(
        principal.getName(),
        id,
        new UserAdministrationService.RoleChange(
            request.roles(), request.expectedVersion(), request.confirmation()),
        key);
    return ResponseEntity.noContent().build();
  }

  record RoleChangeRequest(
      @NotEmpty @Size(max = 4) Set<UserAccount.@NotNull Role> roles,
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 320) String confirmation) {}
}
