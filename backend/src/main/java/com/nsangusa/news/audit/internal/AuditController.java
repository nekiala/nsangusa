package com.nsangusa.news.audit.internal;

import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMINISTRATOR')")
class AuditController {
  private final AuditService audit;

  AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping("/api/v1/admin/audit-records")
  ResponseEntity<AuditService.AuditPage> list(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) UUID targetId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(audit.list(action, targetType, actorId, targetId, from, to, page, size));
  }
}
