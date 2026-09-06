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
  private final String publicBaseUrl;
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
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.users = users;
    this.tokens = tokens;
    this.passwords = passwords;
    this.mailSender = mailSender;
    this.externalIdentities = externalIdentities;
    this.newsletter = newsletter;
    this.sessions = sessions;
    this.properties = properties;
    this.audit = audit;
    this.publicBaseUrl = publicBaseUrl;
  }

  @Override
  @Transactional
  public UUID register(String email, String password, String displayName) {
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmailIgnoreCase(normalized).isPresent()) {
      throw new IllegalArgumentException("Email is already registered");
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
    newsletter.linkAndFind(id, normalized);
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
    user.verifyEmail();
    audit.record(user.id, "IDENTITY_EMAIL_VERIFIED", "user", user.id, Map.of());
  }

  @Override
  @Transactional
  public void requestPasswordReset(String email) {
    users
        .findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
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
    user.changePassword(passwords.encode(newPassword));
    sessions.revokeAll(user.email);
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
    if (update.displayName() != null) {
      String displayName = update.displayName().trim();
      if (displayName.isEmpty() || displayName.length() > 100) {
        throw new IllegalArgumentException("Display name is invalid");
      }
      user.updateProfile(displayName);
    }
    if (update.newsletterFrequency() != null) {
      String frequency = update.newsletterFrequency().trim().toLowerCase(Locale.ROOT);
      if (!Set.of("instant", "daily", "weekly").contains(frequency)) {
        throw new IllegalArgumentException("Newsletter frequency is invalid");
      }
      newsletter.linkAndFind(user.id, user.email);
      newsletter.updateFrequency(user.id, frequency);
    }
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
  public void deleteAccount(String email) {
    UserAccount user = activeUser(email);
    String originalEmail = user.email;
    tokens.deleteByUserId(user.id);
    externalIdentities.deleteByUserId(user.id);
    newsletter.unlink(user.id);
    sessions.revokeAll(originalEmail);
    user.softDelete(passwords.encode(UUID.randomUUID().toString()), Instant.now());
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

  private String issueToken(UUID userId, String purpose, Duration lifetime) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokens.deleteByUserIdAndPurpose(userId, purpose);
    tokens.save(new VerificationToken(userId, hash(value), purpose, Instant.now().plus(lifetime)));
    return value;
  }

  private VerificationToken findToken(String value) {
    return tokens
        .findByTokenHash(hash(value))
        .orElseThrow(() -> new IllegalArgumentException("Token is invalid or expired"));
  }

  private void send(String to, String subject, String body) {
    var message = new SimpleMailMessage();
    message.setTo(to);
    message.setFrom("news@example.invalid");
    message.setSubject(subject);
    message.setText(body);
    mailSender.send(message);
  }

  private UserAccount activeUser(String email) {
    UserAccount user =
        users
            .findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    if (!user.enabled || user.deletedAt != null) {
      throw new IllegalArgumentException("User not found");
    }
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
        newsletter.linkAndFind(user.id, user.email).orElse(null));
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
