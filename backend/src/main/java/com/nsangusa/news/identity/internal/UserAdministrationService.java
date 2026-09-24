package com.nsangusa.news.identity.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class UserAdministrationService {
  enum Status {
    active,
    unverified,
    disabled,
    deleted
  }

  private final UserAccountRepository users;
  private final IdentityAdministrationLock lock;
  private final DurableCommandExecutor commands;
  private final IdentitySessionService sessions;
  private final AuditService audit;

  UserAdministrationService(
      UserAccountRepository users,
      IdentityAdministrationLock lock,
      DurableCommandExecutor commands,
      IdentitySessionService sessions,
      AuditService audit) {
    this.users = users;
    this.lock = lock;
    this.commands = commands;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  UserPage list(
      String actorEmail, String query, UserAccount.Role role, Status status, int page, int size) {
    administrator(actorEmail, false);
    if (page < 0
        || page > 100_000
        || size < 1
        || size > 100
        || query != null && query.length() > 100) {
      throw new IllegalArgumentException("Invalid user search or page bounds");
    }
    String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    Specification<UserAccount> specification =
        (root, criteria, builder) -> {
          var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
          if (!search.isEmpty()) {
            String pattern =
                "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            predicates.add(
                builder.or(
                    builder.like(builder.lower(root.get("email")), pattern, '\\'),
                    builder.like(builder.lower(root.get("displayName")), pattern, '\\')));
          }
          if (role != null) {
            predicates.add(builder.isMember(role, root.get("roles")));
          }
          if (status == Status.deleted) {
            predicates.add(builder.isNotNull(root.get("deletedAt")));
          } else {
            predicates.add(builder.isNull(root.get("deletedAt")));
            if (status == Status.disabled) {
              predicates.add(builder.isFalse(root.get("enabled")));
            } else if (status == Status.active || status == Status.unverified) {
              predicates.add(builder.isTrue(root.get("enabled")));
              predicates.add(builder.equal(root.get("emailVerified"), status == Status.active));
            }
          }
          return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    var result =
        users.findAll(
            specification,
            PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
    return new UserPage(
        result.map(UserAdministrationService::view).getContent(),
        page,
        size,
        result.getTotalElements());
  }

  @Transactional(readOnly = true)
  UserView get(String actorEmail, UUID id) {
    administrator(actorEmail, false);
    return view(
        users
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
  }

  @Transactional
  void changeRoles(String actorEmail, UUID id, RoleChange request, String key) {
    if (key == null || !key.matches("[!-~]{8,200}")) {
      throw new IllegalArgumentException(
          "An Idempotency-Key of 8-200 printable characters is required");
    }
    lock.acquire();
    UserAccount actor = administrator(actorEmail, true);
    var canonical =
        new CanonicalRoleChange(
            request.roles().stream().map(Enum::name).sorted().toList(),
            request.expectedVersion(),
            request.confirmation());
    commands.execute(
        actor.id,
        key,
        "identity-roles:" + id,
        canonical,
        () -> {
          UserAccount target =
              users
                  .findForAdministration(id)
                  .orElseThrow(
                      () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
          if (target.version != request.expectedVersion()) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "User version changed");
          }
          if (actor.id.equals(id)) {
            throw new IllegalStateException(
                "Administrators cannot change their own roles. Ask another administrator.");
          }
          if (!target.enabled || target.deletedAt != null || !target.emailVerified) {
            throw new IllegalStateException(
                "Only active, verified accounts may have their roles changed");
          }
          if (!target.email.equals(request.confirmation())) {
            throw new IllegalArgumentException(
                "Type the selected user's email address to confirm the role change");
          }
          if (request.roles().isEmpty()
              || request.roles().size() > UserAccount.Role.values().length) {
            throw new IllegalArgumentException("Select at least one allowed role");
          }
          if (target.roles.contains(UserAccount.Role.ADMINISTRATOR)
              && !request.roles().contains(UserAccount.Role.ADMINISTRATOR)
              && users.countActiveWithRole(UserAccount.Role.ADMINISTRATOR) <= 1) {
            throw new IllegalStateException("The last active administrator cannot be removed");
          }
          String before = roleNames(target.roles);
          target.replaceRoles(request.roles());
          users.flush();
          sessions.revokeAllAfterCommit(target.email);
          audit.record(
              actor.id,
              "IDENTITY_ROLES_CHANGED",
              "user",
              target.id,
              Map.of(
                  "before",
                  before,
                  "after",
                  roleNames(target.roles),
                  "version",
                  Long.toString(target.version)));
          return null;
        });
  }

  private UserAccount administrator(String email, boolean forUpdate) {
    var user =
        (forUpdate ? users.findForAuthentication(email) : users.findByEmailIgnoreCase(email))
            .orElseThrow(() -> new AccessDeniedException("Administrator access is required"));
    if (!user.enabled
        || user.deletedAt != null
        || !user.emailVerified
        || !user.roles.contains(UserAccount.Role.ADMINISTRATOR)) {
      throw new AccessDeniedException("Administrator access is required");
    }
    return user;
  }

  private static String roleNames(Set<UserAccount.Role> roles) {
    return roles.stream()
        .map(Enum::name)
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static UserView view(UserAccount user) {
    return new UserView(
        user.id,
        user.email,
        user.displayName,
        user.roles.stream().map(Enum::name).sorted().toList(),
        user.emailVerified,
        user.enabled,
        user.deletedAt,
        user.createdAt,
        user.lastLoginAt,
        user.version,
        user.rolesManagedLocally);
  }

  record RoleChange(Set<UserAccount.Role> roles, long expectedVersion, String confirmation) {}

  private record CanonicalRoleChange(
      List<String> roles, long expectedVersion, String confirmation) {}

  record UserPage(List<UserView> items, int page, int size, long total) {}

  record UserView(
      UUID id,
      String email,
      String displayName,
      List<String> roles,
      boolean emailVerified,
      boolean enabled,
      Instant deletedAt,
      Instant createdAt,
      Instant lastLoginAt,
      long version,
      boolean rolesManagedLocally) {}
}
