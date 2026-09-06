package com.nsangusa.news.newsletter;

import java.util.List;
import java.util.UUID;

public interface NewsletterService {
  SubscriptionRequested subscribe(String email, String consentSource, String frequency);

  void confirm(UUID subscriptionId, String token);

  void unsubscribe(UUID subscriptionId, String token);

  void updatePreferences(UUID subscriptionId, String token, String frequency);

  List<DeliveryView> recentDeliveries(int limit);

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
