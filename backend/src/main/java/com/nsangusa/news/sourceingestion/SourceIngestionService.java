package com.nsangusa.news.sourceingestion;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SourceIngestionService {
  UUID addAccount(
      String accountId,
      String handle,
      String displayName,
      Set<String> topics,
      double relevanceThreshold);

  void setMonitoring(UUID accountId, boolean enabled);

  List<AccountView> listAccounts(boolean includeRemoved);

  AccountView getAccount(UUID accountId);

  void updateAccount(
      UUID accountId,
      String displayName,
      Set<String> topics,
      double relevanceThreshold,
      boolean monitoringEnabled,
      UUID actorId);

  void removeAccount(UUID accountId, String reason, UUID actorId);

  List<BlockedAccountView> listBlockedAccounts();

  void blockAccount(String accountId, String reason, UUID actorId);

  void unblockAccount(String accountId, UUID actorId);

  SourceView getSource(UUID sourcePostId);

  List<SourceSummary> listSources(String accountId, String status, int limit);

  void excludeSource(UUID sourcePostId, String reason, UUID actorId);

  void applyComplianceEdit(UUID sourcePostId, String permittedText, String reason, UUID actorId);

  void applyComplianceDeletion(UUID sourcePostId, String reason, UUID actorId);

  void reconcileCompliance(UUID sourcePostId, boolean successful, String notes, UUID actorId);

  void reconcileProviderPost(UUID sourcePostId, ProviderPostSnapshot snapshot);

  void applyProviderDeletion(UUID sourcePostId, String reason, Instant checkedAt);

  void markProviderChecked(UUID sourcePostId, Instant checkedAt);

  void assertSourcesPublishable(Collection<UUID> sourcePostIds);

  void markSync(
      UUID accountId,
      String lastPostId,
      Instant rateLimitResetAt,
      Integer rateLimitLimit,
      Integer rateLimitRemaining,
      String error);

  default UUID discoverPost(
      UUID monitoredAccountId,
      String postId,
      String canonicalUrl,
      String permittedText,
      Instant publishedAt) {
    return discoverPost(
        monitoredAccountId,
        postId,
        postId,
        null,
        canonicalUrl,
        permittedText,
        publishedAt,
        List.of());
  }

  UUID discoverPost(
      UUID monitoredAccountId,
      String postId,
      String editChainId,
      String conversationId,
      String canonicalUrl,
      String permittedText,
      Instant publishedAt,
      List<SourceRelationshipInput> relationships);

  record ProviderPostSnapshot(
      String postId,
      String editChainId,
      String canonicalUrl,
      String permittedText,
      String conversationId,
      Instant observedAt,
      List<SourceRelationshipInput> relationships) {
    public ProviderPostSnapshot(
        String postId,
        String canonicalUrl,
        String permittedText,
        String conversationId,
        Instant observedAt,
        List<SourceRelationshipInput> relationships) {
      this(postId, postId, canonicalUrl, permittedText, conversationId, observedAt, relationships);
    }
  }

  record SourceRelationshipInput(String relatedPostId, String relationshipType) {}

  record AccountView(
      UUID id,
      String accountId,
      String handle,
      String displayName,
      Set<String> topics,
      double relevanceThreshold,
      boolean monitoringEnabled,
      Instant createdAt,
      Instant removedAt,
      Instant lastSuccessfulSyncAt,
      Instant lastSyncAttemptAt,
      Instant rateLimitResetAt,
      Integer rateLimitLimit,
      Integer rateLimitRemaining,
      String lastPostId,
      String lastError,
      int consecutiveErrors,
      String syncHealth,
      long version) {}

  record BlockedAccountView(String accountId, String reason, Instant createdAt, UUID actorId) {}

  record SourceAssociation(String associationType, UUID associatedId, String state) {}

  record SourceRelationship(UUID id, String relatedPostId, String relationshipType) {}

  record SourceSummary(
      UUID id,
      String postId,
      String accountId,
      String handle,
      String status,
      String reconciliationState,
      Instant publishedAt,
      Instant ingestedAt) {}

  record SourceView(
      UUID id,
      UUID monitoredAccountId,
      String postId,
      String accountId,
      String handle,
      String canonicalUrl,
      String permittedText,
      Instant publishedAt,
      Instant ingestedAt,
      String status,
      String complianceAction,
      String reconciliationState,
      Instant complianceRequestedAt,
      Instant complianceReconciledAt,
      String complianceReason,
      String complianceError,
      Instant excludedAt,
      Instant contentDeletedAt,
      List<SourceAssociation> associations,
      List<SourceRelationship> relationships,
      long version) {}
}
