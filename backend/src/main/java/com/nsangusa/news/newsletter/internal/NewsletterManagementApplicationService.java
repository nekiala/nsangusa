package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.newsletter.NewsletterManagementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
class NewsletterManagementApplicationService implements NewsletterManagementService {
  private final NewsletterManagementRepository management;
  private final NewsletterSubscriptionRepository subscriptions;
  private final NewsletterDeliveryRepository deliveries;
  private final UnsubscribeTokenService tokens;
  private final DurableCommandExecutor commands;
  private final AuditService audit;
  private final java.time.Duration attemptLease;

  NewsletterManagementApplicationService(
      NewsletterManagementRepository management,
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryRepository deliveries,
      UnsubscribeTokenService tokens,
      DurableCommandExecutor commands,
      AuditService audit) {
    this(
        management,
        subscriptions,
        deliveries,
        tokens,
        commands,
        audit,
        java.time.Duration.ofMinutes(10));
  }

  @org.springframework.beans.factory.annotation.Autowired
  NewsletterManagementApplicationService(
      NewsletterManagementRepository management,
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryRepository deliveries,
      UnsubscribeTokenService tokens,
      DurableCommandExecutor commands,
      AuditService audit,
      @org.springframework.beans.factory.annotation.Value("${news.newsletter.attempt-lease:PT10M}")
          java.time.Duration attemptLease) {
    this.management = management;
    this.subscriptions = subscriptions;
    this.deliveries = deliveries;
    this.tokens = tokens;
    this.commands = commands;
    this.audit = audit;
    this.attemptLease = attemptLease;
  }

  @Override
  @Transactional
  public void requestPreferenceLink(String email) {
    subscriptions
        .findLockedByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
        .filter(
            subscription ->
                "confirmed".equals(subscription.status) && subscription.verifiedAt != null)
        .ifPresent(
            subscription -> {
              if (management.enqueueLink(subscription, Instant.now())) {
                audit.record(
                    null,
                    "newsletter.preferences.link_requested",
                    "newsletter_subscription",
                    subscription.id,
                    Map.of("delivery", "queued"));
              }
            });
  }

  @Override
  public PreferenceView preferences(UUID subscriptionId, String token) {
    var authorization = authorize(subscriptionId, token);
    return new PreferenceView(
        authorization.subscription.status,
        authorization.subscription.frequency,
        authorization.link.expiresAt());
  }

  @Override
  @Transactional
  public void updatePreferences(
      UUID subscriptionId, String token, String frequency, String requestKey) {
    if (frequency == null) throw new IllegalArgumentException("Newsletter frequency is required");
    var authorization = authorize(subscriptionId, token);
    commands.execute(
        subscriptionId,
        requestKey,
        "POST /api/v1/newsletter/preferences/" + subscriptionId,
        Map.of("frequency", frequency, "authorization", authorization.link.id()),
        () -> {
          authorization.subscription.changeFrequency(frequency);
          audit.record(
              null,
              "newsletter.preferences.updated",
              "newsletter_subscription",
              subscriptionId,
              Map.of("frequency", frequency, "authorization", "email-token"));
          return null;
        });
  }

  @Override
  public Page<SubscriptionView> subscriptions(String status, String frequency, int page, int size) {
    paging(page, size);
    allowed(status, Set.of("pending", "confirmed", "unsubscribed", "suppressed"));
    allowed(frequency, Set.of("immediate", "daily", "weekly", "all"));
    return management.subscriptions(status, frequency, page, size);
  }

  @Override
  public Page<ConsentView> consent(UUID subscriptionId, int page, int size) {
    paging(page, size);
    return management.consent(subscriptionId, page, size);
  }

  @Override
  public Page<CampaignView> campaigns(String type, String status, int page, int size) {
    paging(page, size);
    allowed(type, Set.of("immediate", "daily", "weekly"));
    allowed(status, Set.of("pending", "dispatching", "completed"));
    return management.campaigns(type, status, page, size);
  }

  @Override
  public Page<DeliveryView> deliveries(
      String status, UUID subscriptionId, String campaignKey, int page, int size) {
    paging(page, size);
    allowed(
        status,
        Set.of(
            "pending",
            "provider_accepted",
            "delivery_confirmed",
            "failed",
            "bounced",
            "reconciliation_required",
            "reconciled"));
    if (campaignKey != null && campaignKey.length() > 200)
      throw new IllegalArgumentException("Invalid campaign key");
    return management.deliveries(status, subscriptionId, campaignKey, page, size);
  }

  @Override
  public Page<AttemptView> attempts(UUID deliveryId, int page, int size) {
    paging(page, size);
    return management.attempts(deliveryId, page, size);
  }

  @Override
  public Page<SuppressionView> suppressions(String reason, int page, int size) {
    paging(page, size);
    allowed(reason, Set.of("bounce", "complaint"));
    return management.suppressions(reason, page, size);
  }

  @Override
  @Transactional
  public void reconcile(UUID deliveryId, UUID actorId, Reconciliation request, String requestKey) {
    if (request == null
        || request.outcome() == null
        || !Set.of("provider_accepted", "not_sent", "abandoned").contains(request.outcome())
        || request.evidenceReference() == null
        || !request.evidenceReference().matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{2,199}")) {
      throw new IllegalArgumentException(
          "Provide an outcome and a non-personal evidence reference");
    }
    if (request.providerMessageId() != null
        && !request.providerMessageId().matches("[A-Za-z0-9<][A-Za-z0-9._@<>:/-]{0,199}")) {
      throw new IllegalArgumentException("Invalid provider message identifier");
    }
    if ("provider_accepted".equals(request.outcome())
        && (request.providerMessageId() == null || request.providerMessageId().isBlank())) {
      throw new IllegalArgumentException(
          "Provider acceptance requires a provider message identifier");
    }
    commands.execute(
        actorId,
        requestKey,
        "POST /api/v1/newsletter/admin/deliveries/" + deliveryId + "/reconciliation",
        request,
        () -> {
          var delivery =
              deliveries
                  .findLockedById(deliveryId)
                  .orElseThrow(
                      () ->
                          new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
          if (!"reconciliation_required".equals(delivery.status)
              && !("pending".equals(delivery.status)
                  && delivery.attemptStartedAt != null
                  && delivery.attemptStartedAt.isBefore(Instant.now().minus(attemptLease)))
              && !"failed".equals(delivery.status)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Only uncertain or failed sends can be reconciled");
          }
          management.recordReconciliation(deliveryId, actorId, request);
          audit.record(
              actorId,
              "newsletter.delivery.reconciled",
              "newsletter_delivery",
              deliveryId,
              Map.of(
                  "outcome", request.outcome(), "evidenceReference", request.evidenceReference()));
          return null;
        });
  }

  private Authorization authorize(UUID subscriptionId, String supplied) {
    UUID requestId;
    try {
      if (supplied == null || supplied.length() > 128) throw new IllegalArgumentException();
      requestId = UUID.fromString(supplied.split("\\.", 2)[0]);
    } catch (IllegalArgumentException exception) {
      throw invalidLink();
    }
    var link = management.preferenceLink(requestId, subscriptionId).orElseThrow(this::invalidLink);
    var subscription = subscriptions.findById(subscriptionId).orElseThrow(this::invalidLink);
    if (!"confirmed".equals(subscription.status)
        || subscription.verifiedAt == null
        || !link.verifiedAt().equals(subscription.verifiedAt)
        || !link.expiresAt().isAfter(Instant.now())
        || !MessageDigest.isEqual(
            tokens
                .preferenceTokenFor(link.id(), subscriptionId, link.expiresAt(), link.verifiedAt())
                .getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII))) {
      throw invalidLink();
    }
    return new Authorization(subscription, link);
  }

  private ResponseStatusException invalidLink() {
    return new ResponseStatusException(
        HttpStatus.GONE, "This preference link is invalid or expired");
  }

  private static void paging(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
  }

  private static void allowed(String value, Set<String> values) {
    if (value != null && !value.isEmpty() && !values.contains(value))
      throw new IllegalArgumentException("Invalid filter");
  }

  private record Authorization(
      NewsletterSubscription subscription, NewsletterManagementRepository.PreferenceLink link) {}
}
