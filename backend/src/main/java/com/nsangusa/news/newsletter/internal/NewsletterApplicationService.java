package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.newsletter.NewsletterService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterApplicationService implements NewsletterService {
  private final NewsletterSubscriptionRepository subscriptions;
  private final EmailDeliveryProvider mailSender;
  private final NewsletterDeliveryRepository deliveries;
  private final UnsubscribeTokenService unsubscribeTokens;
  private final AuditService audit;
  private final String publicBaseUrl;
  private final SecureRandom random = new SecureRandom();

  NewsletterApplicationService(
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryRepository deliveries,
      UnsubscribeTokenService unsubscribeTokens,
      EmailDeliveryProvider mailSender,
      AuditService audit,
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.subscriptions = subscriptions;
    this.deliveries = deliveries;
    this.unsubscribeTokens = unsubscribeTokens;
    this.mailSender = mailSender;
    this.audit = audit;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
  }

  @Override
  @Transactional
  public SubscriptionRequested subscribe(String email, String consentSource, String frequency) {
    String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
    if (subscriptions.findByEmail(normalized).isPresent()) {
      throw new IllegalArgumentException("Subscription already exists");
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID id = UUID.randomUUID();
    var subscription =
        new NewsletterSubscription(
            id, normalized, consentSource, frequency, token, unsubscribeTokens.tokenFor(id));
    // The subscriber keeps the language of the page they signed up on.
    subscription.language = com.nsangusa.news.integration.RequestLanguage.current();
    subscriptions.save(subscription);
    var wording = NewsletterText.in(subscription.language);
    String confirmationUrl =
        wording.url(publicBaseUrl, "/newsletter/confirm?id=" + id + "&token=" + token);
    mailSender.send(
        "newsletter-confirmation/" + id,
        normalized,
        wording.confirmSubject(),
        wording.confirmIntro() + "\n" + confirmationUrl + "\n\n" + wording.confirmIgnore(),
        "<p>"
            + org.owasp.encoder.Encode.forHtml(wording.confirmIntro())
            + "</p><p><a href=\""
            + org.owasp.encoder.Encode.forHtmlAttribute(confirmationUrl)
            + "\">"
            + org.owasp.encoder.Encode.forHtml(wording.confirmAction())
            + "</a></p><p>"
            + org.owasp.encoder.Encode.forHtml(wording.confirmIgnore())
            + "</p>");
    audit.record(
        null,
        "newsletter.subscription.requested",
        "newsletter_subscription",
        id,
        java.util.Map.of("frequency", frequency));
    return new SubscriptionRequested(id, "verification_sent");
  }

  @Override
  @Transactional
  public void confirm(UUID subscriptionId, String token) {
    var subscription = find(subscriptionId);
    boolean pending = "pending".equals(subscription.status);
    subscription.confirm(token);
    if (pending)
      audit.record(
          null,
          "newsletter.subscription.confirmed",
          "newsletter_subscription",
          subscriptionId,
          java.util.Map.of());
  }

  @Override
  @Transactional
  public void unsubscribe(UUID subscriptionId, String token) {
    var subscription = find(subscriptionId);
    String previous = subscription.status;
    subscription.unsubscribe(token);
    if (!previous.equals(subscription.status)) {
      audit.record(
          null,
          "newsletter.subscription.unsubscribed",
          "newsletter_subscription",
          subscriptionId,
          java.util.Map.of());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.List<DeliveryView> recentDeliveries(int limit) {
    return deliveries.findTop100ByOrderByCreatedAtDesc().stream()
        .limit(Math.min(Math.max(limit, 1), 100))
        .map(
            delivery ->
                new DeliveryView(
                    delivery.id,
                    delivery.subscriptionId,
                    delivery.articleId,
                    delivery.campaignKey,
                    "delivered".equals(delivery.status)
                        ? (delivery.deliveryConfirmedAt == null
                            ? "provider_accepted"
                            : "delivery_confirmed")
                        : delivery.status,
                    delivery.attemptCount,
                    delivery.failureCode))
        .toList();
  }

  @Override
  @Transactional
  public java.util.Optional<AccountPreference> linkAndFindAccount(
      UUID userId, String verifiedEmail) {
    subscriptions
        .findLockedByEmail(verifiedEmail.trim().toLowerCase(java.util.Locale.ROOT))
        .filter(subscription -> subscription.userId == null || userId.equals(subscription.userId))
        .ifPresent(subscription -> subscription.userId = userId);
    return accountPreference(userId);
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.Optional<AccountPreference> accountPreference(UUID userId) {
    return subscriptions
        .findByUserId(userId)
        .map(
            subscription ->
                new AccountPreference(
                    subscription.id, subscription.status, subscription.frequency));
  }

  @Override
  @Transactional
  public void updateAccountFrequency(UUID userId, String frequency) {
    var subscription =
        subscriptions
            .findByUserId(userId)
            .orElseThrow(
                () -> new IllegalArgumentException("No active newsletter subscription is linked"));
    subscription.changeFrequency(frequency);
    audit.record(
        userId,
        "newsletter.preferences.updated",
        "newsletter_subscription",
        subscription.id,
        java.util.Map.of("frequency", frequency, "authorization", "account"));
  }

  @Override
  @Transactional
  public void unsubscribeAndUnlinkAccount(UUID userId) {
    subscriptions
        .findByUserId(userId)
        .ifPresent(
            subscription -> {
              subscription.withdrawConsent();
              subscription.userId = null;
              subscription.verificationTokenHash =
                  "invalidated:" + subscription.id + ":" + (subscription.version + 1);
              audit.record(
                  userId,
                  "newsletter.account.unlinked",
                  "newsletter_subscription",
                  subscription.id,
                  java.util.Map.of());
            });
  }

  private NewsletterSubscription find(UUID id) {
    return subscriptions
        .findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
  }
}
