package com.nsangusa.news.newsletter.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterDeliveryReservationService {
  private final NewsletterDeliveryRepository deliveries;

  NewsletterDeliveryReservationService(NewsletterDeliveryRepository deliveries) {
    this.deliveries = deliveries;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Optional<UUID> reserve(UUID subscriptionId, UUID articleId, String campaignKey) {
    if (deliveries.existsBySubscriptionIdAndCampaignKey(subscriptionId, campaignKey)) {
      return Optional.empty();
    }
    return Optional.of(
        deliveries.saveAndFlush(new NewsletterDelivery(subscriptionId, articleId, campaignKey)).id);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void delivered(UUID deliveryId, String providerMessageId) {
    find(deliveryId).delivered(providerMessageId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void failed(UUID deliveryId, String failureCode) {
    find(deliveryId).failed(failureCode);
  }

  private NewsletterDelivery find(UUID deliveryId) {
    return deliveries
        .findById(deliveryId)
        .orElseThrow(() -> new IllegalStateException("Newsletter delivery reservation missing"));
  }
}
