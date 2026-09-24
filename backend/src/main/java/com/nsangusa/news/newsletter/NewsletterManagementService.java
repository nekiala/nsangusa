package com.nsangusa.news.newsletter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NewsletterManagementService {
  void requestPreferenceLink(String email);

  PreferenceView preferences(UUID subscriptionId, String token);

  void updatePreferences(UUID subscriptionId, String token, String frequency, String requestKey);

  Page<SubscriptionView> subscriptions(String status, String frequency, int page, int size);

  Page<ConsentView> consent(UUID subscriptionId, int page, int size);

  Page<CampaignView> campaigns(String type, String status, int page, int size);

  Page<DeliveryView> deliveries(
      String status, UUID subscriptionId, String campaignKey, int page, int size);

  Page<AttemptView> attempts(UUID deliveryId, int page, int size);

  Page<SuppressionView> suppressions(String reason, int page, int size);

  void reconcile(UUID deliveryId, UUID actorId, Reconciliation request, String requestKey);

  record Page<T>(List<T> items, int page, int size, long total) {}

  record PreferenceView(String status, String frequency, Instant expiresAt) {}

  record SubscriptionView(
      UUID id,
      String status,
      String frequency,
      String consentSource,
      Instant consentAt,
      Instant verifiedAt,
      Instant unsubscribedAt,
      boolean accountLinked) {}

  record ConsentView(UUID id, String action, String source, Instant occurredAt) {}

  record CampaignView(
      UUID id,
      String campaignKey,
      UUID articleId,
      String campaignType,
      String status,
      Instant createdAt,
      Instant completedAt,
      long recipientCount,
      long acceptedCount,
      long failedCount,
      long reconciliationCount) {}

  record DeliveryView(
      UUID id,
      UUID subscriptionId,
      UUID articleId,
      String campaignKey,
      String status,
      int attemptCount,
      String failureCode,
      Instant createdAt,
      Instant providerAcceptedAt,
      Instant deliveryConfirmedAt,
      Instant attemptStartedAt,
      String providerMessageId,
      boolean providerIdempotencyApplied,
      Instant reconciledAt,
      String reconciliationOutcome,
      String reconciliationEvidence) {}

  record AttemptView(
      UUID id,
      int attemptNumber,
      String status,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      String providerMessageId) {}

  record SuppressionView(UUID id, String reason, Instant createdAt, UUID subscriptionId) {}

  record Reconciliation(String outcome, String evidenceReference, String providerMessageId) {}
}
