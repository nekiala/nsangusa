package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class UserAdministrationServiceTests {
  @Mock UserAccountRepository users;
  @Mock IdentityAdministrationLock lock;
  @Mock DurableCommandExecutor commands;
  @Mock IdentitySessionService sessions;
  @Mock AuditService audit;
  UserAdministrationService service;
  UserAccount actor;
  UserAccount target;

  @BeforeEach
  void setup() {
    service = new UserAdministrationService(users, lock, commands, sessions, audit);
    actor = account("admin@example.test", UserAccount.Role.ADMINISTRATOR);
    target = account("reader@example.test", UserAccount.Role.READER);
  }

  @Test
  void rolesUseCurrentDatabaseActorAndDurableReceiptAndInvalidateSessionsAfterCommit() {
    authorized();
    execute();
    service.changeRoles(
        actor.email,
        target.id,
        request(Set.of(UserAccount.Role.READER, UserAccount.Role.EDITOR)),
        "role-request-1");

    assertThat(target.roles)
        .containsExactlyInAnyOrder(UserAccount.Role.READER, UserAccount.Role.EDITOR);
    assertThat(target.rolesManagedLocally).isTrue();
    assertThat(target.authenticationValidAfter).isNotNull();
    verify(lock).acquire();
    verify(commands)
        .execute(
            eq(actor.id), eq("role-request-1"), eq("identity-roles:" + target.id), any(), any());
    verify(sessions).revokeAllAfterCommit(target.email);
    verify(audit)
        .record(eq(actor.id), eq("IDENTITY_ROLES_CHANGED"), eq("user"), eq(target.id), any());
  }

  @Test
  void staleRolesAreRejectedWithoutAuditOrSessionEffects() {
    authorized();
    execute();
    target.version = 2;
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email,
                    target.id,
                    request(Set.of(UserAccount.Role.EDITOR)),
                    "stale-request"))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(sessions, audit);
  }

  @Test
  void ownRoleChangesAndUnverifiedPromotionsAreBlocked() {
    authorized();
    execute();
    when(users.findForAdministration(actor.id)).thenReturn(Optional.of(actor));
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email,
                    actor.id,
                    request(Set.of(UserAccount.Role.READER)),
                    "self-request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("own roles");
    target.emailVerified = false;
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email,
                    target.id,
                    request(Set.of(UserAccount.Role.ADMINISTRATOR)),
                    "unverified-request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("verified");
    verifyNoInteractions(sessions, audit);
  }

  @Test
  void lastAdministratorAndMistypedConfirmationAreProtected() {
    authorized();
    execute();
    target.roles = new java.util.HashSet<>(Set.of(UserAccount.Role.ADMINISTRATOR));
    when(users.countActiveWithRole(UserAccount.Role.ADMINISTRATOR)).thenReturn(1L);
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email,
                    target.id,
                    request(Set.of(UserAccount.Role.READER)),
                    "last-request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("last active administrator");
    var wrong =
        new UserAdministrationService.RoleChange(
            Set.of(UserAccount.Role.EDITOR), 0, "wrong@example.test");
    assertThatThrownBy(() -> service.changeRoles(actor.email, target.id, wrong, "wrong-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected user's email");
    verifyNoInteractions(sessions, audit);
  }

  @Test
  void authorizationRunsBeforeReceiptLookupEvenForFormerAdministrators() {
    actor.roles.clear();
    when(users.findForAuthentication(actor.email)).thenReturn(Optional.of(actor));
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email,
                    target.id,
                    request(Set.of(UserAccount.Role.EDITOR)),
                    "old-receipt"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(commands, sessions, audit);
  }

  @Test
  void requestsNeedAnIdempotencyKey() {
    assertThatThrownBy(
            () ->
                service.changeRoles(
                    actor.email, target.id, request(Set.of(UserAccount.Role.EDITOR)), null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(commands, users, lock, sessions, audit);
  }

  @Test
  void externallyMappedRolesCannotRestoreLocallyRemovedPrivileges() {
    target.replaceRoles(Set.of(UserAccount.Role.READER));
    target.addRoles(Set.of(UserAccount.Role.ADMINISTRATOR));
    assertThat(target.roles).containsExactly(UserAccount.Role.READER);
  }

  private void authorized() {
    when(users.findForAuthentication(actor.email)).thenReturn(Optional.of(actor));
    when(users.findForAdministration(target.id)).thenReturn(Optional.of(target));
  }

  private void execute() {
    doAnswer(invocation -> ((Supplier<String>) invocation.getArgument(4)).get())
        .when(commands)
        .execute(any(), anyString(), anyString(), any(), any());
  }

  private UserAdministrationService.RoleChange request(Set<UserAccount.Role> roles) {
    return new UserAdministrationService.RoleChange(roles, 0, target.email);
  }

  static UserAccount account(String email, UserAccount.Role role) {
    return new UserAccount(UUID.randomUUID(), email, email, "hash", true, Set.of(role));
  }
}
