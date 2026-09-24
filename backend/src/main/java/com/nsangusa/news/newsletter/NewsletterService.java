package com.nsangusa.news.newsletter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NewsletterService {
  SubscriptionRequested subscribe(String email, String consentSource, String frequency);

  void confirm(UUID subscriptionId, String token);

  void unsubscribe(UUID subscriptionId, String token);

  List<DeliveryView> recentDeliveries(int limit);

  /** Called only after identity has verified the account's email; linking never grants consent. */
  Optional<AccountPreference> linkAndFindAccount(UUID userId, String verifiedEmail);

  Optional<AccountPreference> accountPreference(UUID userId);

  void updateAccountFrequency(UUID userId, String frequency);

  void unsubscribeAndUnlinkAccount(UUID userId);

  record AccountPreference(UUID subscriptionId, String status, String frequency) {}

  record SubscriptionRequested(UUID subscriptionId, String status) {}

  record DeliveryView(
      UUID id,
      UUID subscriptionId,
      UUID articleId,
      String campaignKey,
      String status,
      int attemptCount,
      String failureCode) {}
}
