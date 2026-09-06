package com.nsangusa.news.newsletter.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterWebhookService {
  private final ProviderWebhookRepository webhooks;
  private final NewsletterDeliveryRepository deliveries;
  private final NewsletterSubscriptionRepository subscriptions;
  private final SuppressionEntryRepository suppressions;
  private final ObjectMapper mapper;
  private final byte[] secret;

  NewsletterWebhookService(
      ProviderWebhookRepository webhooks,
      NewsletterDeliveryRepository deliveries,
      NewsletterSubscriptionRepository subscriptions,
      SuppressionEntryRepository suppressions,
      ObjectMapper mapper,
      @Value("${news.newsletter.webhook-secret}") String secret) {
    if (secret.length() < 32) {
      throw new IllegalStateException(
          "Newsletter webhook secret must contain at least 32 characters");
    }
    this.webhooks = webhooks;
    this.deliveries = deliveries;
    this.subscriptions = subscriptions;
    this.suppressions = suppressions;
    this.mapper = mapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @Transactional
  void process(String provider, String signature, String body) {
    if (!validSignature(signature, body)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Invalid provider webhook signature");
    }
    try {
      var payload = mapper.readTree(body);
      String externalId = payload.required("id").asText();
      if (webhooks.existsByProviderAndExternalEventId(provider, externalId)) {
        return;
      }
      var webhook = webhooks.save(new ProviderWebhook(provider, externalId, true, sha256(body)));
      String type = payload.required("type").asText();
      String messageId = payload.path("messageId").asText();
      String email = payload.path("email").asText().trim().toLowerCase(java.util.Locale.ROOT);
      if (java.util.Set.of("bounce", "complaint").contains(type)) {
        deliveries.findByProviderMessageId(messageId).ifPresent(delivery -> delivery.bounced(type));
        subscriptions
            .findByEmail(email)
            .ifPresent(
                subscription -> {
                  subscription.suppress();
                  String emailHash = sha256(email);
                  if (!suppressions.existsByEmailHash(emailHash)) {
                    suppressions.save(new SuppressionEntry(emailHash, type));
                  }
                });
      }
      webhook.processed();
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("Malformed provider webhook", exception);
    }
  }

  private boolean validSignature(String signature, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      String expected =
          HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      return signature != null
          && MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.US_ASCII),
              signature.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to validate webhook", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
