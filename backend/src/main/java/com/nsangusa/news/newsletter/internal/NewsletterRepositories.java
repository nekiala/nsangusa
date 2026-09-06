package com.nsangusa.news.newsletter.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NewsletterSubscriptionRepository extends JpaRepository<NewsletterSubscription, UUID> {
  Optional<NewsletterSubscription> findByEmail(String email);

  List<NewsletterSubscription> findByStatusAndFrequencyIn(String status, List<String> frequencies);
}

interface NewsletterDeliveryRepository extends JpaRepository<NewsletterDelivery, UUID> {
  boolean existsBySubscriptionIdAndCampaignKey(UUID subscriptionId, String campaignKey);

  Optional<NewsletterDelivery> findByProviderMessageId(String providerMessageId);

  List<NewsletterDelivery> findTop100ByOrderByCreatedAtDesc();
}

interface NewsletterCampaignRepository extends JpaRepository<NewsletterCampaign, UUID> {
  boolean existsByCampaignKey(String campaignKey);

  Optional<NewsletterCampaign> findByCampaignKey(String campaignKey);
}

interface SuppressionEntryRepository extends JpaRepository<SuppressionEntry, UUID> {
  boolean existsByEmailHash(String emailHash);
}

interface ProviderWebhookRepository extends JpaRepository<ProviderWebhook, UUID> {
  boolean existsByProviderAndExternalEventId(String provider, String externalEventId);
}
