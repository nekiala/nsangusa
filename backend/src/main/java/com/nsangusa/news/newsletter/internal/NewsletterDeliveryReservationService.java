package com.nsangusa.news.newsletter.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterDeliveryReservationService {
  private static final Duration MAXIMUM_PROVIDER_IDEMPOTENCY_WINDOW = Duration.ofHours(24);

  private final NewsletterDeliveryRepository deliveries;
  private final Duration idempotencyWindow;
  private final Duration attemptLease;
  private final boolean providerSupportsIdempotency;

  NewsletterDeliveryReservationService(NewsletterDeliveryRepository deliveries) {
    this(deliveries, Duration.ofHours(24), Duration.ofMinutes(10));
  }

  NewsletterDeliveryReservationService(
      NewsletterDeliveryRepository deliveries, Duration idempotencyWindow) {
    this(deliveries, idempotencyWindow, Duration.ofMinutes(10));
  }

  NewsletterDeliveryReservationService(
      NewsletterDeliveryRepository deliveries, Duration idempotencyWindow, Duration attemptLease) {
    this(deliveries, idempotencyWindow, attemptLease, true);
  }

  @Autowired
  NewsletterDeliveryReservationService(
      NewsletterDeliveryRepository deliveries,
      @Value("${news.newsletter.idempotency-window:PT24H}") Duration idempotencyWindow,
      @Value("${news.newsletter.attempt-lease:PT10M}") Duration attemptLease,
      EmailDeliveryProvider provider) {
    this(deliveries, idempotencyWindow, attemptLease, provider.supportsIdempotency());
  }

  NewsletterDeliveryReservationService(
      NewsletterDeliveryRepository deliveries,
      Duration idempotencyWindow,
      Duration attemptLease,
      boolean providerSupportsIdempotency) {
    if (idempotencyWindow.isNegative() || idempotencyWindow.isZero()) {
      throw new IllegalArgumentException("Newsletter idempotency window must be positive");
    }
    if (idempotencyWindow.compareTo(MAXIMUM_PROVIDER_IDEMPOTENCY_WINDOW) > 0) {
      throw new IllegalArgumentException(
          "Newsletter idempotency window cannot exceed the provider's 24-hour guarantee");
    }
    if (attemptLease.isNegative()
        || attemptLease.isZero()
        || attemptLease.compareTo(idempotencyWindow) >= 0) {
      throw new IllegalArgumentException(
          "Newsletter attempt lease must be positive and shorter than the idempotency window");
    }
    this.deliveries = deliveries;
    this.idempotencyWindow = idempotencyWindow;
    this.attemptLease = attemptLease;
    this.providerSupportsIdempotency = providerSupportsIdempotency;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Optional<DeliveryAttempt> reserve(UUID subscriptionId, UUID articleId, String campaignKey) {
    var existing = deliveries.findLockedBySubscriptionIdAndCampaignKey(subscriptionId, campaignKey);
    if (existing.isPresent()) {
      var delivery = existing.orElseThrow();
      if ("delivered".equals(delivery.status)
          || "bounced".equals(delivery.status)
          || "reconciled".equals(delivery.status)
          || "reconciliation_required".equals(delivery.status)) {
        return Optional.empty();
      }
      if (!delivery.providerIdempotencyApplied || !providerSupportsIdempotency) {
        if (delivery.attemptInProgress(Instant.now(), attemptLease)) return Optional.empty();
        delivery.requireReconciliation("provider_idempotency_not_confirmed");
        return Optional.empty();
      }
      if (delivery.createdAt.isBefore(Instant.now().minus(idempotencyWindow))) {
        delivery.requireReconciliation("provider_idempotency_window_expired");
        return Optional.empty();
      }
      Instant now = Instant.now();
      if (delivery.attemptInProgress(now, attemptLease)) {
        return Optional.of(
            new DeliveryAttempt(
                delivery.id, delivery.attemptToken, delivery.providerIdempotencyKey));
      }
      UUID attemptToken = delivery.beginAttempt(now);
      delivery.providerIdempotencyApplied = providerSupportsIdempotency;
      return Optional.of(
          new DeliveryAttempt(delivery.id, attemptToken, delivery.providerIdempotencyKey));
    }
    var delivery = new NewsletterDelivery(subscriptionId, articleId, campaignKey);
    UUID attemptToken = delivery.beginAttempt(Instant.now());
    delivery.providerIdempotencyApplied = providerSupportsIdempotency;
    deliveries.saveAndFlush(delivery);
    return Optional.of(
        new DeliveryAttempt(delivery.id, attemptToken, delivery.providerIdempotencyKey));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void delivered(UUID deliveryId, UUID attemptToken, String providerMessageId) {
    findLocked(deliveryId).delivered(attemptToken, providerMessageId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void failed(UUID deliveryId, UUID attemptToken, String failureCode) {
    var delivery = findLocked(deliveryId);
    if (delivery.failed(attemptToken, failureCode) && !delivery.providerIdempotencyApplied) {
      delivery.requireReconciliation("provider_acceptance_unknown");
    }
  }

  private NewsletterDelivery findLocked(UUID deliveryId) {
    return deliveries
        .findLockedById(deliveryId)
        .orElseThrow(() -> new IllegalStateException("Newsletter delivery reservation missing"));
  }

  record DeliveryAttempt(UUID deliveryId, UUID attemptToken, String providerIdempotencyKey) {}
}
