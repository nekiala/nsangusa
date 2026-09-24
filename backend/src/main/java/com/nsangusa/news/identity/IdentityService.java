package com.nsangusa.news.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface IdentityService {
  UUID register(String email, String password, String displayName);

  void verifyEmail(String token);

  void requestVerification(String email);

  void requestPasswordReset(String email);

  void resetPassword(String token, String newPassword);

  UserProfile profile(String email);

  UserProfile updateProfile(String email, ProfileUpdate update);

  AccountDataExport exportData(String email);

  void deleteAccount(String email, long expectedVersion);

  List<UserSession> sessions(String email, String currentSessionId);

  void revokeSession(String email, String sessionId);

  UUID requireAnyRole(String email, Set<String> allowedRoles);

  List<CommunityUser> findCommunityUsers(String actorEmail, String query, int limit);

  CommunityUserPage findCommunityUsers(String actorEmail, String query, int page, int size);

  CommunityUser communityUser(String actorEmail, UUID userId);

  Map<UUID, String> displayNames(Set<UUID> userIds);

  record CommunityUser(UUID id, String displayName, boolean staff, boolean active) {}

  record CommunityUserPage(List<CommunityUser> items, int page, int size, long total) {}

  record ProfileUpdate(String displayName, String newsletterFrequency, long expectedVersion) {}

  record UserProfile(
      UUID id,
      String email,
      String displayName,
      Set<String> roles,
      boolean emailVerified,
      Instant createdAt,
      Instant lastLoginAt,
      NewsletterPreference newsletter,
      long version) {}

  record NewsletterPreference(UUID subscriptionId, String status, String frequency) {}

  record ExternalIdentityView(
      String issuer, String subject, String emailAtLink, Instant linkedAt, Instant lastLoginAt) {}

  record AccountDataExport(
      Instant generatedAt, UserProfile profile, List<ExternalIdentityView> externalIdentities) {}

  record UserSession(
      String id, Instant createdAt, Instant lastAccessedAt, Instant expiresAt, boolean current) {}
}
