package com.nsangusa.news.identity.internal;

import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoginSecurityService {
  private final UserAccountRepository users;
  private final IdentityProperties properties;
  private final AuditService audit;

  LoginSecurityService(
      UserAccountRepository users, IdentityProperties properties, AuditService audit) {
    this.users = users;
    this.properties = properties;
    this.audit = audit;
  }

  @Transactional
  public void failed(String username) {
    users
        .findForAuthentication(normalize(username))
        .ifPresent(
            user -> {
              user.recordFailedLogin(
                  Instant.now(),
                  properties.getMaximumFailedAttempts(),
                  properties.getFailedAttemptWindow(),
                  properties.getLockDuration());
              audit.record(
                  user.id,
                  user.isLocked(Instant.now()) ? "IDENTITY_LOGIN_LOCKED" : "IDENTITY_LOGIN_FAILED",
                  "user",
                  user.id,
                  Map.of("attempts", Integer.toString(user.failedLoginAttempts)));
            });
  }

  @Transactional
  public void succeeded(String username, String authenticationMethod) {
    users
        .findForAuthentication(normalize(username))
        .ifPresent(
            user -> {
              user.recordSuccessfulLogin(Instant.now());
              audit.record(
                  user.id,
                  "IDENTITY_LOGIN_SUCCEEDED",
                  "user",
                  user.id,
                  Map.of("method", authenticationMethod));
            });
  }

  private static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }
}
