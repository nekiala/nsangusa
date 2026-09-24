package com.nsangusa.news.newsletter.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface NewsletterSubscriptionRepository extends JpaRepository<NewsletterSubscription, UUID> {
  Optional<NewsletterSubscription> findByEmail(String email);

  Optional<NewsletterSubscription> findByUserId(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select subscription from NewsletterSubscription subscription where subscription.email = :email")
  Optional<NewsletterSubscription> findLockedByEmail(String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select subscription from NewsletterSubscription subscription where subscription.id = :id")
  Optional<NewsletterSubscription> findLockedById(UUID id);

  List<NewsletterSubscription> findByStatusAndFrequencyIn(String status, List<String> frequencies);
}

interface NewsletterDeliveryRepository extends JpaRepository<NewsletterDelivery, UUID> {
  boolean existsBySubscriptionIdAndCampaignKey(UUID subscriptionId, String campaignKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select delivery
        from NewsletterDelivery delivery
       where delivery.subscriptionId = :subscriptionId
         and delivery.campaignKey = :campaignKey
      """)
  Optional<NewsletterDelivery> findLockedBySubscriptionIdAndCampaignKey(
      UUID subscriptionId, String campaignKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select delivery from NewsletterDelivery delivery where delivery.id = :id")
  Optional<NewsletterDelivery> findLockedById(UUID id);

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
