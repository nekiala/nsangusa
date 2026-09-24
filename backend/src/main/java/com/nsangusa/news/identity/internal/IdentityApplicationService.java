package com.nsangusa.news.identity.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.identity.IdentityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityApplicationService implements IdentityService {
  private final UserAccountRepository users;
  private final VerificationTokenRepository tokens;
  private final PasswordEncoder passwords;
  private final JavaMailSender mailSender;
  private final ExternalIdentityRepository externalIdentities;
  private final NewsletterAccountLinkRepository newsletter;
  private final IdentitySessionService sessions;
  private final IdentityProperties properties;
  private final AuditService audit;
  private final IdentityAdministrationLock administrationLock;
  private final String publicBaseUrl;
  private final String fromAddress;
  private final SecureRandom random = new SecureRandom();

  IdentityApplicationService(
      UserAccountRepository users,
      VerificationTokenRepository tokens,
      PasswordEncoder passwords,
      JavaMailSender mailSender,
      ExternalIdentityRepository externalIdentities,
      NewsletterAccountLinkRepository newsletter,
      IdentitySessionService sessions,
      IdentityProperties properties,
      AuditService audit,
      IdentityAdministrationLock administrationLock,
      @Value("${news.public-base-url}") String publicBaseUrl,
      @Value("${news.identity.from-address:${news.newsletter.from-address:news@example.invalid}}")
          String fromAddress) {
    this.users = users;
    this.tokens = tokens;
    this.passwords = passwords;
    this.mailSender = mailSender;
    this.externalIdentities = externalIdentities;
    this.newsletter = newsletter;
    this.sessions = sessions;
    this.properties = properties;
    this.audit = audit;
    this.administrationLock = administrationLock;
    this.publicBaseUrl = publicBaseUrl;
    this.fromAddress = fromAddress;
  }

  @Override
  @Transactional
  public UUID register(String email, String password, String displayName) {
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmailIgnoreCase(normalized).isPresent()) {
      // Registration deliberately has the same public response for an existing address.
      passwords.encode(password);
      return UUID.randomUUID();
    }
    UUID id = UUID.randomUUID();
    users.save(
        new UserAccount(
            id,
            normalized,
            displayName.trim(),
            passwords.encode(password),
            false,
            Set.of(UserAccount.Role.READER)));
    String token = issueToken(id, "email_verification", properties.getVerificationTokenTtl());
    send(
        normalized, "Verify your Nsangusa account", publicBaseUrl + "/verify-email?token=" + token);
    audit.record(id, "IDENTITY_REGISTERED", "user", id, Map.of());
    return id;
  }

  @Override
  @Transactional
  public void verifyEmail(String token) {
    var verification = findToken(token);
    verification.consume("email_verification");
    UserAccount user =
        users
            .findById(verification.userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    requireEnabled(user);
    user.verifyEmail();
    newsletter.linkAndFind(user.id, user.email);
    audit.record(user.id, "IDENTITY_EMAIL_VERIFIED", "user", user.id, Map.of());
  }

  @Override
  @Transactional
  public void requestVerification(String email) {
    users
        .findForAuthentication(email.trim().toLowerCase(Locale.ROOT))
        .filter(user -> user.enabled && user.deletedAt == null && !user.emailVerified)
        .ifPresent(
            user -> {
              String token =
                  issueToken(user.id, "email_verification", properties.getVerificationTokenTtl());
              send(
                  user.email,
                  "Verify your Nsangusa account",
                  publicBaseUrl + "/verify-email?token=" + token);
              audit.record(user.id, "IDENTITY_VERIFICATION_REQUESTED", "user", user.id, Map.of());
            });
  }

  @Override
  @Transactional
  public void requestPasswordReset(String email) {
    users
        .findForAuthentication(email.trim().toLowerCase(Locale.ROOT))
        .filter(user -> user.enabled && user.deletedAt == null)
        .ifPresent(
            user -> {
              String token =
                  issueToken(user.id, "password_reset", properties.getPasswordResetTokenTtl());
              send(
                  user.email,
                  "Reset your Nsangusa password",
                  publicBaseUrl + "/password-reset?token=" + token);
              audit.record(user.id, "IDENTITY_PASSWORD_RESET_REQUESTED", "user", user.id, Map.of());
            });
  }

  @Override
  @Transactional
  public void resetPassword(String token, String newPassword) {
    var reset = findToken(token);
    reset.consume("password_reset");
    UserAccount user =
        users
            .findById(reset.userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    requireEnabled(user);
    user.changePassword(passwords.encode(newPassword));
    sessions.revokeAllAfterCommit(user.email);
    audit.record(user.id, "IDENTITY_PASSWORD_RESET", "user", user.id, Map.of());
  }

  @Override
  @Transactional
  public UserProfile profile(String email) {
    return profileFor(activeUser(email));
  }

  @Override
  @Transactional
  public UserProfile updateProfile(String email, ProfileUpdate update) {
    UserAccount user = activeUser(email);
    if (user.version != update.expectedVersion()) {
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "Profile version changed");
    }
    if (update.displayName() != null) {
      String displayName = update.displayName().trim();
      if (displayName.isEmpty() || displayName.length() > 100) {
        throw new IllegalArgumentException("Display name is invalid");
      }
      user.updateProfile(displayName);
    }
    if (update.newsletterFrequency() != null) {
      String frequency = update.newsletterFrequency().trim().toLowerCase(Locale.ROOT);
      if ("instant".equals(frequency)) {
        frequency = "immediate";
      }
      if (!Set.of("immediate", "daily", "weekly").contains(frequency)) {
        throw new IllegalArgumentException("Newsletter frequency is invalid");
      }
      newsletter.linkAndFind(user.id, user.email);
      newsletter.updateFrequency(user.id, frequency);
    }
    user.profileUpdatedAt = Instant.now();
    users.flush();
    audit.record(user.id, "IDENTITY_PROFILE_UPDATED", "user", user.id, Map.of());
    return profileFor(user);
  }

  @Override
  @Transactional
  public AccountDataExport exportData(String email) {
    UserAccount user = activeUser(email);
    List<ExternalIdentityView> linkedIdentities =
        externalIdentities.findByUserIdOrderByCreatedAt(user.id).stream()
            .map(
                identity ->
                    new ExternalIdentityView(
                        identity.issuer,
                        identity.subject,
                        identity.emailAtLink,
                        identity.createdAt,
                        identity.lastLoginAt))
            .toList();
    audit.record(user.id, "IDENTITY_DATA_EXPORTED", "user", user.id, Map.of());
    return new AccountDataExport(Instant.now(), profileFor(user), linkedIdentities);
  }

  @Override
  @Transactional
  public void deleteAccount(String email, long expectedVersion) {
    administrationLock.acquire();
    UserAccount user =
        users
            .findForAuthentication(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    requireEnabled(user);
    if (user.version != expectedVersion) {
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "Profile version changed");
    }
    if (user.roles.contains(UserAccount.Role.ADMINISTRATOR)
        && users.countActiveWithRole(UserAccount.Role.ADMINISTRATOR) <= 1) {
      throw new IllegalStateException("The last active administrator cannot delete their account");
    }
    String originalEmail = user.email;
    tokens.deleteByUserId(user.id);
    externalIdentities.deleteByUserId(user.id);
    newsletter.unsubscribeAndUnlink(user.id);
    user.softDelete(passwords.encode(UUID.randomUUID().toString()), Instant.now());
    users.flush();
    sessions.revokeAllAfterCommit(originalEmail);
    audit.record(user.id, "IDENTITY_ACCOUNT_ANONYMIZED", "user", user.id, Map.of());
  }

  @Override
  @Transactional
  public List<UserSession> sessions(String email, String currentSessionId) {
    activeUser(email);
    return sessions.list(email, currentSessionId);
  }

  @Override
  @Transactional
  public void revokeSession(String email, String sessionId) {
    activeUser(email);
    sessions.revoke(email, sessionId);
  }

  @Override
  @Transactional(readOnly = true)
  public UUID requireAnyRole(String email, Set<String> allowedRoles) {
    if (allowedRoles == null
        || allowedRoles.isEmpty()
        || allowedRoles.stream().anyMatch(java.util.Objects::isNull)
        || !Set.of("READER", "MODERATOR", "EDITOR", "ADMINISTRATOR").containsAll(allowedRoles)) {
      throw new IllegalArgumentException("Allowed account roles are required");
    }
    var user =
        users
            .findByEmailIgnoreCase(email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
            .orElseThrow(
                () ->
                    new org.springframework.security.access.AccessDeniedException(
                        "Account access is required"));
    if (!user.enabled
        || !user.emailVerified
        || user.deletedAt != null
        || user.roles.stream().map(Enum::name).noneMatch(allowedRoles::contains)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "The required account role is missing");
    }
    return user.id;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CommunityUser> findCommunityUsers(String actorEmail, String query, int limit) {
    return findCommunityUsers(actorEmail, query, 0, limit).items();
  }

  @Override
  @Transactional(readOnly = true)
  public CommunityUserPage findCommunityUsers(String actorEmail, String query, int page, int size) {
    requireAnyRole(actorEmail, Set.of("MODERATOR", "ADMINISTRATOR"));
    String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (search.length() < 2
        || search.length() > 100
        || size < 1
        || size > 20
        || page < 0
        || page > 10_000) {
      throw new IllegalArgumentException(
          "Use a search of 2-100 characters, size 1-20 and page 0-10000");
    }
    String pattern =
        "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    var result =
        users.findAll(
            (root, criteria, builder) ->
                builder.and(
                    builder.isTrue(root.get("enabled")),
                    builder.isTrue(root.get("emailVerified")),
                    builder.isNull(root.get("deletedAt")),
                    builder.or(
                        builder.like(builder.lower(root.get("displayName")), pattern, '\\'),
                        builder.like(builder.lower(root.get("email")), pattern, '\\'))),
            org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by("displayName", "id")));
    return new CommunityUserPage(
        result.stream().map(IdentityApplicationService::communityView).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public CommunityUser communityUser(String actorEmail, UUID userId) {
    requireAnyRole(actorEmail, Set.of("MODERATOR", "ADMINISTRATOR"));
    return communityView(
        users
            .findById(userId)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Account not found")));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, String> displayNames(Set<UUID> userIds) {
    if (userIds == null
        || userIds.size() > 100
        || userIds.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("At most 100 account identifiers are allowed");
    }
    return users.findAllById(userIds).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                user -> user.id,
                user -> user.deletedAt == null ? user.displayName : "Deleted user"));
  }

  private static CommunityUser communityView(UserAccount user) {
    return new CommunityUser(
        user.id,
        user.deletedAt == null ? user.displayName : "Deleted user",
        user.roles.stream().anyMatch(role -> role != UserAccount.Role.READER),
        user.enabled && user.emailVerified && user.deletedAt == null);
  }

  private String issueToken(UUID userId, String purpose, Duration lifetime) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokens.deleteByUserIdAndPurpose(userId, purpose);
    tokens.save(new VerificationToken(userId, hash(value), purpose, Instant.now().plus(lifetime)));
    return value;
  }

  private VerificationToken findToken(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("Token is invalid or expired");
    }
    return tokens
        .findByTokenHash(hash(value))
        .orElseThrow(() -> new IllegalArgumentException("Token is invalid or expired"));
  }

  private void send(String to, String subject, String body) {
    var message = new SimpleMailMessage();
    message.setTo(to);
    message.setFrom(fromAddress);
    message.setSubject(subject);
    message.setText(body);
    mailSender.send(message);
  }

  private UserAccount activeUser(String email) {
    UserAccount user =
        users
            .findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    requireEnabled(user);
    return user;
  }

  private UserProfile profileFor(UserAccount user) {
    return new UserProfile(
        user.id,
        user.email,
        user.displayName,
        user.roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
        user.emailVerified,
        user.createdAt,
        user.lastLoginAt,
        user.emailVerified ? newsletter.linkAndFind(user.id, user.email).orElse(null) : null,
        user.version);
  }

  private static void requireEnabled(UserAccount user) {
    if (!user.enabled || user.deletedAt != null) {
      throw new IllegalArgumentException("User not found");
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
