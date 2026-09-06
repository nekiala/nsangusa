package com.nsangusa.news.identity;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface IdentityService {
  UUID register(String email, String password, String displayName);

  void verifyEmail(String token);

  void requestPasswordReset(String email);

  void resetPassword(String token, String newPassword);

  UserProfile profile(String email);

  UserProfile updateProfile(String email, ProfileUpdate update);

  AccountDataExport exportData(String email);

  void deleteAccount(String email);

  List<UserSession> sessions(String email, String currentSessionId);

  void revokeSession(String email, String sessionId);

  record ProfileUpdate(String displayName, String newsletterFrequency) {}

  record UserProfile(
      UUID id,
      String email,
      String displayName,
      Set<String> roles,
      boolean emailVerified,
      Instant createdAt,
      Instant lastLoginAt,
      NewsletterPreference newsletter) {}

  record NewsletterPreference(UUID subscriptionId, String status, String frequency) {}

  record ExternalIdentityView(
      String issuer, String subject, String emailAtLink, Instant linkedAt, Instant lastLoginAt) {}

  record AccountDataExport(
      Instant generatedAt, UserProfile profile, List<ExternalIdentityView> externalIdentities) {}

  record UserSession(
      String id, Instant createdAt, Instant lastAccessedAt, Instant expiresAt, boolean current) {}
}
