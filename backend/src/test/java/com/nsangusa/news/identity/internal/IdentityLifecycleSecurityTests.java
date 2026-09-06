package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.audit.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityLifecycleSecurityTests {
  @Mock UserAccountRepository users;
  @Mock VerificationTokenRepository tokens;
  @Mock AuditService audit;

  @Test
  void oidcRoleMappingDefaultsToReaderAndOnlyUsesConfiguredMappings() {
    var properties = new IdentityProperties();
    properties.getOidc().setRoleMapping(Map.of("news-admin", "ADMINISTRATOR"));
    var mapper = new OidcRoleMapper(properties);

    assertThat(mapper.map(Map.of("roles", Set.of("unknown"))))
        .containsExactly(UserAccount.Role.READER);
    assertThat(mapper.map(Map.of("roles", Set.of("news-admin", "unknown"))))
        .containsExactlyInAnyOrder(UserAccount.Role.READER, UserAccount.Role.ADMINISTRATOR);
  }

  @Test
  void repeatedBadCredentialsLockAndSuccessfulLoginClearsTrackingWithAudit() {
    var properties = new IdentityProperties();
    properties.setMaximumFailedAttempts(2);
    properties.setFailedAttemptWindow(Duration.ofMinutes(10));
    properties.setLockDuration(Duration.ofMinutes(30));
    var user =
        new UserAccount(
            UUID.randomUUID(),
            "reader@example.test",
            "Reader",
            "hash",
            true,
            Set.of(UserAccount.Role.READER));
    when(users.findForAuthentication("reader@example.test")).thenReturn(Optional.of(user));
    var security = new LoginSecurityService(users, properties, audit);

    security.failed("READER@example.test");
    security.failed("reader@example.test");

    assertThat(user.isLocked(Instant.now())).isTrue();
    assertThat(user.failedLoginAttempts).isEqualTo(2);
    verify(audit)
        .record(
            eq(user.id),
            eq("IDENTITY_LOGIN_LOCKED"),
            eq("user"),
            eq(user.id),
            eq(Map.of("attempts", "2")));

    security.succeeded("reader@example.test", "password");

    assertThat(user.isLocked(Instant.now())).isFalse();
    assertThat(user.failedLoginAttempts).isZero();
    assertThat(user.lastLoginAt).isNotNull();
    verify(audit)
        .record(
            eq(user.id),
            eq("IDENTITY_LOGIN_SUCCEEDED"),
            eq("user"),
            eq(user.id),
            eq(Map.of("method", "password")));
  }

  @Test
  void accountAnonymizationDisablesEveryAuthenticationPath() {
    UUID id = UUID.randomUUID();
    var user =
        new UserAccount(
            id, "person@example.test", "Person", "hash", true, Set.of(UserAccount.Role.EDITOR));

    user.softDelete("disabled-hash", Instant.now());

    assertThat(user.email).isEqualTo("deleted+" + id + "@users.invalid");
    assertThat(user.displayName).isEqualTo("Deleted user");
    assertThat(user.enabled).isFalse();
    assertThat(user.emailVerified).isFalse();
    assertThat(user.localCredentialsEnabled).isFalse();
    assertThat(user.roles).isEmpty();
    assertThat(user.deletedAt).isNotNull();
  }

  @Test
  void tokenCleanupDeletesExpiredAndOldConsumedTokens() {
    var properties = new IdentityProperties();
    properties.setConsumedTokenRetention(Duration.ofHours(12));
    var cleanup = new VerificationTokenCleanup(tokens, properties);
    var now = ArgumentCaptor.forClass(Instant.class);
    var usedBefore = ArgumentCaptor.forClass(Instant.class);

    cleanup.cleanup();

    verify(tokens).deleteExpiredAndConsumed(now.capture(), usedBefore.capture());
    assertThat(Duration.between(usedBefore.getValue(), now.getValue()))
        .isCloseTo(Duration.ofHours(12), Duration.ofSeconds(1));
  }
}
