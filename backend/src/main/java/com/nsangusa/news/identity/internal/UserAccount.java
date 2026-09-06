package com.nsangusa.news.identity.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserAccount {
  enum Role {
    READER,
    MODERATOR,
    EDITOR,
    ADMINISTRATOR
  }

  @Id UUID id;

  @Column(nullable = false, unique = true)
  String email;

  @Column(nullable = false)
  String displayName;

  @Column(nullable = false)
  String passwordHash;

  @Column(nullable = false)
  boolean localCredentialsEnabled;

  @Column(nullable = false)
  boolean emailVerified;

  @Column(nullable = false)
  boolean enabled;

  @Column(nullable = false)
  Instant createdAt;

  Instant deletedAt;

  int failedLoginAttempts;

  Instant failedLoginWindowStartedAt;

  Instant lockedUntil;

  Instant lastLoginAt;

  @Version long version;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "role")
  @Enumerated(EnumType.STRING)
  Set<Role> roles = new HashSet<>();

  protected UserAccount() {}

  UserAccount(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      boolean emailVerified,
      Set<Role> roles) {
    this(id, email, displayName, passwordHash, true, emailVerified, roles);
  }

  UserAccount(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      boolean localCredentialsEnabled,
      boolean emailVerified,
      Set<Role> roles) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.localCredentialsEnabled = localCredentialsEnabled;
    this.emailVerified = emailVerified;
    this.enabled = true;
    this.createdAt = Instant.now();
    this.roles.addAll(roles);
  }

  void verifyEmail() {
    this.emailVerified = true;
  }

  void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
    this.localCredentialsEnabled = true;
    clearLoginFailures();
  }

  void updateProfile(String displayName) {
    this.displayName = displayName;
  }

  void addRoles(Set<Role> roles) {
    this.roles.addAll(roles);
  }

  boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  void recordFailedLogin(
      Instant now,
      int maximumAttempts,
      java.time.Duration window,
      java.time.Duration lockDuration) {
    if (failedLoginWindowStartedAt == null
        || !failedLoginWindowStartedAt.plus(window).isAfter(now)) {
      failedLoginAttempts = 0;
      failedLoginWindowStartedAt = now;
    }
    failedLoginAttempts++;
    if (failedLoginAttempts >= maximumAttempts) {
      lockedUntil = now.plus(lockDuration);
    }
  }

  void recordSuccessfulLogin(Instant now) {
    clearLoginFailures();
    lastLoginAt = now;
  }

  void softDelete(String disabledPasswordHash, Instant now) {
    email = "deleted+" + id + "@users.invalid";
    displayName = "Deleted user";
    passwordHash = disabledPasswordHash;
    localCredentialsEnabled = false;
    emailVerified = false;
    enabled = false;
    deletedAt = now;
    roles.clear();
    clearLoginFailures();
  }

  private void clearLoginFailures() {
    failedLoginAttempts = 0;
    failedLoginWindowStartedAt = null;
    lockedUntil = null;
  }
}
