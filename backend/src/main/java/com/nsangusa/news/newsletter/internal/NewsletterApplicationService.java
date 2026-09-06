package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.newsletter.NewsletterService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterApplicationService implements NewsletterService {
  private final NewsletterSubscriptionRepository subscriptions;
  private final JavaMailSender mailSender;
  private final NewsletterDeliveryRepository deliveries;
  private final UnsubscribeTokenService unsubscribeTokens;
  private final String publicBaseUrl;
  private final SecureRandom random = new SecureRandom();

  NewsletterApplicationService(
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryRepository deliveries,
      UnsubscribeTokenService unsubscribeTokens,
      JavaMailSender mailSender,
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.subscriptions = subscriptions;
    this.deliveries = deliveries;
    this.unsubscribeTokens = unsubscribeTokens;
    this.mailSender = mailSender;
    this.publicBaseUrl = publicBaseUrl;
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
    subscriptions.save(
        new NewsletterSubscription(
            id, normalized, consentSource, frequency, token, unsubscribeTokens.tokenFor(id)));
    var message = new SimpleMailMessage();
    message.setTo(normalized);
    message.setFrom("news@example.invalid");
    message.setSubject("Confirm your newsletter subscription");
    message.setText(
        "Confirm your subscription:\n"
            + publicBaseUrl
            + "/newsletter/confirm?id="
            + id
            + "&token="
            + token);
    mailSender.send(message);
    return new SubscriptionRequested(id, "verification_sent");
  }

  @Override
  @Transactional
  public void confirm(UUID subscriptionId, String token) {
    find(subscriptionId).confirm(token);
  }

  @Override
  @Transactional
  public void unsubscribe(UUID subscriptionId, String token) {
    find(subscriptionId).unsubscribe(token);
  }

  @Override
  @Transactional
  public void updatePreferences(UUID subscriptionId, String token, String frequency) {
    find(subscriptionId).updateFrequency(token, frequency);
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
                    delivery.status,
                    delivery.attemptCount,
                    delivery.failureCode))
        .toList();
  }

  private NewsletterSubscription find(UUID id) {
    return subscriptions
        .findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
  }
}
