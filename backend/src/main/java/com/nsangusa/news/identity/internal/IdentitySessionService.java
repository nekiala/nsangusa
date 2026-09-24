package com.nsangusa.news.identity.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.identity.IdentityService;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
class IdentitySessionService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(IdentitySessionService.class);
  private final FindByIndexNameSessionRepository<? extends Session> sessions;
  private final UserAccountRepository users;
  private final AuditService audit;

  IdentitySessionService(
      ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions,
      UserAccountRepository users,
      AuditService audit) {
    this.sessions = sessions.getIfAvailable();
    this.users = users;
    this.audit = audit;
  }

  List<IdentityService.UserSession> list(String email, String currentSessionId) {
    if (sessions == null) {
      throw new IllegalStateException("Session management is unavailable");
    }
    return sessions.findByPrincipalName(email).values().stream()
        .map(
            session ->
                new IdentityService.UserSession(
                    session.getId(),
                    session.getCreationTime(),
                    session.getLastAccessedTime(),
                    session.getLastAccessedTime().plus(session.getMaxInactiveInterval()),
                    session.getId().equals(currentSessionId)))
        .sorted(Comparator.comparing(IdentityService.UserSession::lastAccessedAt).reversed())
        .toList();
  }

  void revoke(String email, String sessionId) {
    if (sessions == null || !sessions.findByPrincipalName(email).containsKey(sessionId)) {
      throw new IllegalArgumentException("Session not found");
    }
    sessions.deleteById(sessionId);
    var user =
        users
            .findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    audit.record(
        user.id,
        "IDENTITY_SESSION_REVOKED",
        "user",
        user.id,
        java.util.Map.of("sessionId", sessionId));
  }

  void revokeAll(String email) {
    if (sessions == null) {
      throw new IllegalStateException("Session management is unavailable");
    }
    sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
  }

  void revokeAllAfterCommit(String email) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      revokeAll(email);
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                try {
                  revokeAll(email);
                } catch (RuntimeException failure) {
                  // The database cutoff also rejects old sessions if Redis cleanup is unavailable.
                  log.warn(
                      "Session cleanup failed after identity change: {}",
                      failure.getClass().getSimpleName());
                }
              }
            });
  }
}
