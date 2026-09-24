package com.nsangusa.news.identity.internal;

import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OidcAccountService {
  private final UserAccountRepository users;
  private final ExternalIdentityRepository identities;
  private final PasswordEncoder passwords;
  private final OidcRoleMapper roles;
  private final NewsletterAccountLinkRepository newsletter;
  private final IdentityProperties properties;
  private final AuditService audit;

  OidcAccountService(
      UserAccountRepository users,
      ExternalIdentityRepository identities,
      PasswordEncoder passwords,
      OidcRoleMapper roles,
      NewsletterAccountLinkRepository newsletter,
      IdentityProperties properties,
      AuditService audit) {
    this.users = users;
    this.identities = identities;
    this.passwords = passwords;
    this.roles = roles;
    this.newsletter = newsletter;
    this.properties = properties;
    this.audit = audit;
  }

  @Transactional
  UserAccount provision(
      String issuer, String subject, Map<String, Object> claims, Instant authenticatedAt)
      throws OAuth2AuthenticationException {
    String normalizedIssuer = required(issuer, "issuer", 500);
    String normalizedSubject = required(subject, "subject", 500);
    var linked = identities.findByIssuerAndSubject(normalizedIssuer, normalizedSubject);
    if (linked.isPresent()) {
      ExternalIdentity identity = linked.get();
      UserAccount user = activeUser(identity.userId);
      user.addRoles(roles.map(claims));
      identity.recordLogin(authenticatedAt);
      if (user.emailVerified) {
        newsletter.linkAndFind(user.id, user.email);
      }
      return user;
    }

    String email = normalizeEmail(claims.get("email"));
    boolean verified = verifiedEmail(claims.get("email_verified"));
    if (properties.getOidc().isRequireVerifiedEmail() && !verified) {
      throw oauthError("unverified_email", "The identity provider did not verify the email");
    }

    Set<UserAccount.Role> mappedRoles = roles.map(claims);
    var existingAccount = users.findByEmailIgnoreCase(email);
    if (existingAccount.isPresent() && !verified) {
      throw oauthError(
          "unverified_email", "A verified identity-provider email is required to link an account");
    }
    UserAccount user;
    if (existingAccount.isPresent()) {
      user = existingAccount.get();
      if (!user.enabled || user.deletedAt != null) {
        throw oauthError("account_disabled", "The linked account is unavailable");
      }
      user.verifyEmail();
      user.addRoles(mappedRoles);
    } else {
      user =
          users.save(
              new UserAccount(
                  UUID.randomUUID(),
                  email,
                  displayName(claims, email),
                  passwords.encode(UUID.randomUUID().toString()),
                  false,
                  verified,
                  mappedRoles));
    }

    identities.save(
        new ExternalIdentity(user.id, normalizedIssuer, normalizedSubject, email, authenticatedAt));
    if (user.emailVerified) {
      newsletter.linkAndFind(user.id, email);
    }
    audit.record(
        user.id, "IDENTITY_OIDC_LINKED", "user", user.id, Map.of("issuer", normalizedIssuer));
    return user;
  }

  private UserAccount activeUser(UUID userId) {
    UserAccount user =
        users.findById(userId).orElseThrow(() -> oauthError("account_missing", "User not found"));
    if (!user.enabled || user.deletedAt != null) {
      throw oauthError("account_disabled", "The linked account is unavailable");
    }
    return user;
  }

  private static String normalizeEmail(Object claim) {
    String email = required(claim, "email", 320).toLowerCase(Locale.ROOT);
    if (!email.contains("@")) {
      throw oauthError("invalid_email", "The identity provider email is invalid");
    }
    return email;
  }

  private static boolean verifiedEmail(Object claim) {
    return Boolean.TRUE.equals(claim) || "true".equalsIgnoreCase(String.valueOf(claim));
  }

  private static String displayName(Map<String, Object> claims, String email) {
    Object claim = claims.get("name");
    if (claim == null || claim.toString().isBlank()) {
      claim = claims.get("preferred_username");
    }
    String value =
        claim == null || claim.toString().isBlank()
            ? email.substring(0, email.indexOf('@'))
            : claim.toString();
    value = value.trim();
    return value.length() <= 100 ? value : value.substring(0, 100);
  }

  private static String required(Object value, String claim, int maximumLength) {
    String normalized = value == null ? "" : value.toString().trim();
    if (normalized.isEmpty() || normalized.length() > maximumLength) {
      throw oauthError("invalid_" + claim, "The identity provider " + claim + " is invalid");
    }
    return normalized;
  }

  private static OAuth2AuthenticationException oauthError(String code, String description) {
    return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
  }
}
